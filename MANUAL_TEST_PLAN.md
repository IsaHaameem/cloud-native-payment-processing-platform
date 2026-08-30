# PaymentFlow — manual test checklist

Run after the automated validation is green. Assumes the local stack is up
(`docker compose up -d` from the repo root) unless a step says otherwise. Every command is
copy-pasteable; every step says what "worked" looks like and what failure looks like.

`sk_test_…` keys only unless a step explicitly says live.

---

## A. New merchant

- **URL:** `http://localhost:3000/signup`
- **Do:** register with a fresh email → complete onboarding.
- **Expect:** you land on the dashboard; `identity` and `merchant` services each have a new row
  (`docker compose exec postgres psql -U paymentflow -c "select email from identity.users order by created_at desc limit 1"`).
- **Failure:** a 500 on signup usually means `identity-service` or `merchant-service` is not up
  (`docker compose ps`); a redirect loop back to `/login` means the session cookie is not being
  set — check `PORTAL_SESSION_SECRET` in `.env`.

## B. Create a test API key

- **URL:** `http://localhost:3000/developers/api-keys`
- **Do:** create a **Secret** key in **Test** mode.
- **Expect:** the full `sk_test_…` value is shown **exactly once**, with a copy button; after you
  dismiss the dialog only the prefix is visible.
- **Failure:** the value showing again on reload is a real bug (report it); a key that starts
  `sk_live_` means the mode toggle is on Live.

## C. Node.js integration

- **Get the SDK:** `cd sdks/node && npm ci && npm run build` (not on npm yet).
- **Code:** a server file that does `new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY, baseUrl: 'http://localhost:8080' })`,
  then `payments.create({ amountMinor: 1000, currency: 'USD', paymentMethodToken: 'pm_card_visa' })`,
  then `authorize`, then `capture`, then `retrieve`.
- **Expect:** `create` → `status: "created"`; `authorize` → `"authorized"`; `capture` →
  `"captured"` with `capturedAmountMinor: 1000`; `retrieve` → `"captured"`.
- **Failure:** `PaymentFlowConfigurationError` = the key env var is unset or has whitespace;
  `AuthenticationError` = wrong/revoked key; `ApiConnectionError` = the gateway (`:8080`) is down.

## D. Python integration

- **Get the SDK:** `cd sdks/python && pip install .` (not on PyPI yet).
- **Code:** `PaymentFlow(base_url="http://localhost:8080")` (reads `PAYMENTFLOW_API_KEY`), then
  `payments.create(amount_minor=1000, currency="USD", payment_method_token="pm_card_visa")`, then
  `authorize`, `capture`, `retrieve`. Objects are dicts: `payment["id"]`, `payment["status"]`.
- **Expect:** same status progression as C.
- **Failure:** `PaymentFlowConfigurationError` (a `ValueError`) on construction = key problem.

## E. Java integration

- **Stage the SDK:** `cd sdks/java && ./gradlew publishToMavenLocal` (not on Central yet), then
  add `mavenLocal()` + `implementation("dev.paymentflow:paymentflow:0.1.0")`.
- **Code:** `PaymentFlow.builder().apiKey(System.getenv("PAYMENTFLOW_API_KEY")).baseUrl("http://localhost:8080").build()`,
  then `client.payments().create(Payments.params().amountMinor(1000).currency("USD").paymentMethodToken("pm_card_visa"))`,
  then `authorize(id)`, `capture(id)`, `retrieve(id)`. Fields are accessors: `payment.id()`,
  `payment.status()`.
- **Expect:** same progression; `captured.capturedAmountMinor()` == 1000.
- **Failure:** `PaymentFlowConfigurationException` on `build()` = key problem; catch
  `PaymentFlowException` for everything.

## F. Go integration

- **Get the SDK:** in your module's `go.mod` add
  `replace github.com/IsaHaameem/cloud-native-payment-processing-platform/sdks/go => /path/to/repo/sdks/go`,
  then `go mod tidy` (not tagged yet).
- **Code:** `client, err := paymentflow.NewClient("", paymentflow.WithBaseURL("http://localhost:8080"))`
  (reads `PAYMENTFLOW_API_KEY`), then `client.Payments.Create(ctx, paymentflow.CreatePaymentParams{AmountMinor: 1000, Currency: "USD", PaymentMethodToken: "pm_card_visa"})`,
  then `Authorize`, `Capture`, `Retrieve`.
- **Expect:** same progression; `captured.CapturedAmountMinor == 1000`.
- **Failure:** `NewClient` returns a non-nil error for a missing/whitespace key; branch other
  errors with `errors.As(err, new(*paymentflow.InvalidRequestError))` etc.

## G. cURL integration

```bash
BASE=http://localhost:8080
KEY=sk_test_...
PID=$(curl -sS $BASE/v1/payments \
  -H "Authorization: Bearer $KEY" -H "PaymentFlow-Version: 2026-08-01" \
  -H "Idempotency-Key: $(uuidgen)" -H "Content-Type: application/json" \
  -d '{"amountMinor":1000,"currency":"USD","paymentMethodToken":"pm_card_visa"}' | jq -r .id)
curl -sS $BASE/v1/payments/$PID/authorize -X POST -H "Authorization: Bearer $KEY" \
  -H "PaymentFlow-Version: 2026-08-01" -H "Idempotency-Key: $(uuidgen)"
curl -sS $BASE/v1/payments/$PID/capture -X POST -H "Authorization: Bearer $KEY" \
  -H "PaymentFlow-Version: 2026-08-01" -H "Idempotency-Key: $(uuidgen)"
curl -sS $BASE/v1/payments/$PID -H "Authorization: Bearer $KEY" -H "PaymentFlow-Version: 2026-08-01" | jq .status
```

- **Expect:** final `jq .status` prints `"captured"`.
- **Failure:** `401` with `authentication_error` = key; `400 VALIDATION_ERROR` = a missing/typo'd
  field; a `409 IDEMPOTENCY_CONFLICT` = you reused a key across two different requests.

## H. Knitt payment

- **Start:** `cd mock-project/knitt && npm ci && npm run dev` (needs `PAYMENTFLOW_API_KEY` in its
  own `.env`). Storefront at `http://localhost:5173` (or the port its README states).
- **Do:** add an item → checkout → pick `pm_card_visa`.
- **Expect:** the confirmation page shows a PaymentFlow **payment id** and live **status**
  reaching `captured`. Re-submitting the same checkout does **not** create a second payment
  (derived `Idempotency-Key` per `(orderId, step)`).
- **Failure:** "payment failed" with a real acquirer reason when you picked
  `pm_card_chargeDeclined` is **correct**; an invented reason string is a bug.

## I. PaymentFlow dashboard

- **URL:** `http://localhost:3000/payments`
- **Do:** open the list after running C–H.
- **Expect:** every payment you created appears, most recent first, with the right amount,
  currency, mode badge (Test), and status. Filters (status, currency, date) narrow the list;
  the CSV export downloads.
- **Failure:** an empty list with data in the DB = the session's merchant id doesn't match the
  key's merchant (you're logged in as a different merchant than the key belongs to).

## J. Payment detail

- **URL:** `http://localhost:3000/payments/<id>`
- **Expect:** the full lifecycle timeline (created → authorized → captured), amounts, metadata,
  the raw JSON, and — for a captured payment — Capture/Refund/Void actions with the ones that
  are not legal for the current status disabled.
- **Failure:** a 404 for an id that exists in the list = a merchant-isolation mismatch (as I).

## K. Refund

- **Do:** on a captured payment's detail page, refund a **partial** amount, then the rest.
- **Expect:** status → `partially_refunded` → `refunded`; `refundedAmountMinor` never exceeds
  `capturedAmountMinor`; the refund appears in the payment's refunds and at `/refunds`.
- **Failure:** the platform rejects an over-refund with `invalid_request_error` before writing
  anything — that is correct, not a bug.

## L. Webhook

- **URL:** `http://localhost:3000/developers/webhooks`
- **Do:** register an endpoint pointing at a request-bin or `mock-project/knitt`'s receiver;
  copy the signing secret **now** (shown once). Create + capture a payment.
- **Expect:** a `payment.captured` delivery arrives; its `PaymentFlow-Signature` verifies with
  the secret (any SDK's `constructEvent`/`ConstructEvent`); the delivery shows on the Webhooks
  page with its attempts. `POST /v1/webhook_deliveries/{id}/replay` re-sends it.
- **Failure:** a signature that will not verify against the **raw** body but does against a
  re-serialized one = your receiver parsed the JSON before verifying. Verify first.

## M. Sandbox

- **URL:** `http://localhost:3000/developers/sandbox`
- **Do:** list test cards; create a simulation override (e.g. `FORCE_DECLINE` with a
  `remainingCount`); create a payment and authorize it.
- **Expect:** the override forces the outcome for the next N authorizations, then expires;
  `/v1/test/decisions` explains each decision's `source`. None of this works with a live key
  (403).
- **Failure:** an override with neither `remainingCount` nor `durationSeconds` is refused —
  correct.

## N. Agentic commerce

- **URL:** `http://localhost:3000/agentic` and Knitt's `Assistant` page.
- **Do:** ask the assistant to find a product and buy it.
- **Expect:** the agent proposes tool calls (`search_products`, `create_checkout`,
  `complete_checkout`); the policy engine decides `PERMIT` / `REFUSE` / `REQUIRES_APPROVAL`;
  a permitted purchase goes through the same `/v1` payment API; the action log records a
  redacted, schema-projected summary (never raw model output).
- **Which model:** the service logs the LLM provider at startup. With no `OPENAI_API_KEY` /
  `ANTHROPIC_API_KEY` it uses `ScriptedLlmClient` and says so in the log — the demo still works.
- **Failure:** a money action running without passing the policy engine, or the agent being
  handed a generic HTTP/shell/SQL tool, is a design violation.

## O. Approval flow

- **Do:** configure the refund approval threshold low; have the agent request a refund above it.
- **Expect:** the tool returns `stopReason: "APPROVAL_REQUIRED"` with an `approvalId`; the refund
  does **not** execute; a human calls the approve endpoint (or the portal approval UI); only then
  does the refund run.
- **Failure:** the refund executing before approval is a serious bug.

## P. AI integration prompt

- **URL:** `http://localhost:3000/developers/ai`
- **Do:** pick app type + stack (try **Go** and **Java**, new this pass) + features → copy the
  prompt.
- **Expect:** the prompt names only real endpoints; for an unpublished SDK it says
  "publish-ready but not yet published — build from `sdks/<lang>`, prefer REST"; it never puts a
  key value in the text; it tells the agent to keep the key server-side, use test mode,
  reuse an idempotency key on retry, run tests, list changed files, and commit nothing.
- **Failure:** a `pip install paymentflow` / `npm install paymentflow` line with no
  "not published" caveat is stale — should not happen after this pass.

## Q. Mobile UI

- **Do:** open the portal in a 390×844 viewport (device toolbar). Walk dashboard → payments →
  a payment detail → refunds → webhooks → quickstart.
- **Expect:** no horizontal scroll; the sidebar collapses to a menu; tables scroll inside their
  own container; the mode switch stays reachable; code blocks scroll rather than overflow.
- **Failure:** the page body scrolling sideways, or a table pushing the layout wider than the
  viewport. (No automated viewport test is committed — M23.9.)

## R. Test mode

- **Do:** everything above with an `sk_test_` key and the mode switch on **Test**.
- **Expect:** a "Test mode" badge on every screen; test data is fully isolated from live;
  `mode: "test"` on every object.

## S. Live mode

- **Do:** switch the mode toggle to **Live**, create an `sk_live_` key, run C/G once.
- **Expect:** it works the same, `mode: "live"` on the objects — **and no real money moves.**
  The current platform settles every payment against a *simulated* acquirer in both modes; the
  UI and the AI prompt say so. If any screen implies real funds move in live mode, that copy is
  wrong and should be fixed.
- **Failure:** treating live mode as real payment processing. It is not, yet.
