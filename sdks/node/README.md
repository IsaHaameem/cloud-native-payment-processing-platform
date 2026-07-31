# paymentflow — the PaymentFlow SDK for Node.js

The official client for the PaymentFlow payments API. TypeScript-first, **zero runtime
dependencies**, ESM and CommonJS.

> **Not published.** `package.json` is marked `private`, which `npm publish` refuses.
> Publishing to a public registry is irreversible and effectively claims a name, so it needs an
> explicit decision rather than a passing build. See [Status](#status).

---

## Contents

- [Installation](#installation)
- [Authentication](#authentication)
- [Creating a client](#creating-a-client)
- [Payments](#payments)
- [Refunds](#refunds)
- [Pagination](#pagination)
- [Errors](#errors)
- [Webhooks](#webhooks)
- [Idempotency and retries](#idempotency-and-retries)
- [Tracing a call](#tracing-a-call)
- [TypeScript](#typescript)
- [Resources](#resources)
- [What is deliberately absent](#what-is-deliberately-absent)
- [Development](#development)
- [Status](#status)

---

## Installation

```bash
npm install paymentflow
```

Node 18 or newer, for the built-in `fetch`. That is the whole reason for the floor: this
package has no runtime dependencies, and a payments SDK that drags in a transitive dependency
tree is a supply-chain liability for every integrator.

Both module systems work:

```ts
import { PaymentFlow } from 'paymentflow';       // ESM
```

```js
const { PaymentFlow } = require('paymentflow');  // CommonJS
```

---

## Authentication

Your secret API key, as `Authorization: Bearer sk_…`, on every request. The SDK does this for
you; you only have to supply the key.

**The key alone decides the mode.** An `sk_test_` key reads and writes test data, an `sk_live_`
key live data, and nothing else — no header, no option, no body field — can change that. That
is why this SDK has no `mode` option and will not get one: a switch that appeared to move a
client between modes would be a lie.

---

## Creating a client

```ts
import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY });
```

`apiKey` falls back to `PAYMENTFLOW_API_KEY`, so in most deployments it can be omitted entirely.

| Option | Default | Notes |
|---|---|---|
| `apiKey` | `process.env.PAYMENTFLOW_API_KEY` | Required, one way or the other. |
| `baseUrl` | `https://api.paymentflow.dev` | Override for a local stack. |
| `apiVersion` | the revision this build was generated against | Sent as `PaymentFlow-Version`. |
| `timeout` | `30_000` | Milliseconds, per HTTP attempt. |
| `maxRetries` | `3` | Applies only to retryable outcomes. |
| `fetch` | the global one | Injectable, for tests and proxies. |

```ts
import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({
  apiKey: process.env.PAYMENTFLOW_API_KEY,
  baseUrl: 'http://localhost:8080',
  timeout: 10_000,
  maxRetries: 5,
});
```

Anything that cannot work is rejected by the constructor rather than by the first call — an
invalid base URL, a negative timeout, an API key with a trailing newline out of a `.env` file.
A credential problem should surface on the line that configured it, not hours later on a
payment.

Construct one client per API key and share it. There is no connection pool to warm and no state
to keep, so a client is cheap — but building one per request re-reads the environment and
re-validates on every call for nothing.

---

## Payments

Every method is exactly one published operation and exactly one HTTP request.

```ts
import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY });

// Amounts are integers in the currency's minor unit. 1000 in USD is $10.00 —
// there are no floating-point amounts anywhere in this API.
const payment = await client.payments.create({
  amountMinor: 1000,
  currency: 'USD',
  description: 'Order A-1234',
  metadata: { orderId: 'A-1234' },
});

const retrieved = await client.payments.retrieve(payment.id ?? '');
const authorized = await client.payments.authorize(payment.id ?? '');
const captured = await client.payments.capture(payment.id ?? '');

console.log(retrieved.status, authorized.status, captured.capturedAmountMinor);

// Voiding releases an authorization instead of capturing it.
const other = await client.payments.create({ amountMinor: 800, currency: 'USD' });
await client.payments.authorize(other.id ?? '');
await client.payments.void(other.id ?? '');
```

In test mode, pass a seeded card token to choose the outcome:

```ts
import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY });

const cards = await client.testHelpers.listCards();
for (const card of cards) {
  console.log(card.token, card.description);
}

await client.payments.create({
  amountMinor: 2000,
  currency: 'USD',
  paymentMethodToken: 'tok_visa_approved',
});
```

---

## Refunds

A refund is created by refunding a **payment**, which is where the API puts it — so
`payments.refund()` is the only way to make one, and it resolves to the payment. The new refund
is in `payment.refunds`.

```ts
import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY });

// Omit `amountMinor` to refund everything still refundable.
const payment = await client.payments.refund('pay_1', {
  amountMinor: 500,
  reason: 'requested_by_customer',
});

console.log(payment.refundedAmountMinor, 'of', payment.amountMinor);

// `client.refunds` reads them back.
const refund = await client.refunds.retrieve('re_1');
console.log(refund.status, refund.reason);

for await (const each of await client.refunds.list({ payment: 'pay_1' })) {
  console.log(each.id, each.amountMinor);
}
```

---

## Pagination

List methods return a page that is **also an async iterable**, so the ordinary thing is already
the paginating thing:

```ts
import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY });

let total = 0;
for await (const payment of await client.payments.list({ status: 'captured' })) {
  total += payment.amountMinor ?? 0;
}
console.log(total);
```

It fetches pages as it goes and holds one at a time, so that is safe on an account of any size.
Breaking out of the loop stops making requests.

For manual control, the same object exposes the page directly:

```ts
import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY });

let page = await client.payments.list({ limit: 50 });
while (true) {
  console.log(page.data.length, 'on this page;', page.hasMore ? 'more follow' : 'that is all');
  const next = await page.nextPage();
  if (next === undefined) break;
  page = next;
}
```

Filters carry across pages automatically — re-issuing a page request with different filters
than the cursor was minted under returns a result set that never existed. `metadata` is a
containment filter where every named key must match:

```ts
import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY });

for await (const payment of await client.payments.list({
  metadata: { orderId: 'A-1234', channel: 'web' },
  created_after: '2026-01-01T00:00:00Z',
})) {
  console.log(payment.id);
}
```

Parameter names are the API's own — `starting_after`, `created_after`, `amount_min` — so what
you write is what goes on the wire and the published docs are searchable from the call site.

**Two page shapes**, because the API has two. Cursor pages (`data`, `hasMore`, `nextCursor`)
on most lists; offset pages (`content`, `page`, `size`, `totalElements`, `totalPages`) on
`webhookDeliveries` and `testHelpers.listDecisions`. Both iterate identically; the offset ones
additionally report totals, which a cursor page deliberately does not.

```ts
import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY });

const deliveries = await client.webhookDeliveries.list({ size: 20, sort: ['createdAt,desc'] });
console.log(deliveries.totalElements, 'deliveries across', deliveries.totalPages, 'pages');

for await (const delivery of deliveries) {
  console.log(delivery.id, delivery.status);
}
```

Two lists are **not** paginated on the wire and return plain arrays:
`client.webhookEndpoints.list()` (capped at 16 per mode) and `client.testHelpers.listCards()`.
Wrapping them in a page would invent a `hasMore` no response carries.

---

## Errors

Catching `PaymentFlowError` is already a complete handler. Narrow from there when you can do
something more useful than log.

```ts
import {
  PaymentFlow,
  PaymentFlowError,
  RateLimitError,
  InvalidRequestError,
  IdempotencyError,
  ApiConnectionError,
} from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY });

try {
  await client.payments.capture('pay_1');
} catch (error: unknown) {
  if (error instanceof RateLimitError) {
    // The SDK already waited out anything short; reaching here means the interval was
    // longer than it will block for. Schedule rather than retry now.
    console.error('retry in', error.retryAfterSeconds, 'seconds');
  } else if (error instanceof IdempotencyError) {
    // A concurrent request holds the same key. This one may succeed later.
    console.error('conflict:', error.code);
  } else if (error instanceof InvalidRequestError) {
    // Will be rejected identically however many times it is sent.
    console.error(error.code, error.message, error.param);
    for (const field of error.fieldErrors ?? []) {
      console.error(field.field, field.message);
    }
  } else if (error instanceof ApiConnectionError) {
    // No response at all, so whether it took effect is genuinely unknown — which is
    // exactly why mutations carry an idempotency key.
    console.error('no response after', error.attempts, 'attempts');
  } else if (error instanceof PaymentFlowError) {
    console.error(error.name, error.message, 'request', error.requestId);
  } else {
    throw error;
  }
}
```

| Class | Raised for |
|---|---|
| `AuthenticationError` | the key is missing, malformed or unrecognised |
| `PermissionError` | the key is valid and not allowed to do this |
| `InvalidRequestError` | a validation failure, an unknown id, an impossible state change |
| `IdempotencyError` | an `Idempotency-Key` conflict — may succeed later, unlike the above |
| `RateLimitError` | the rate limit or the daily quota; carries `retryAfterSeconds` |
| `ApiConnectionError` | no response at all: DNS, reset, or the timeout elapsing |
| `ApiError` | the platform failed to handle a request it accepted |

All extend `PaymentFlowError` and carry `code`, `message`, `param`, `fieldErrors`, `requestId`,
`correlationId`, `docUrl`, `statusCode` and `attempts`.

The class is chosen from the response's `type` field, not from the status code, because the
platform distinguishes a retryable 409 from a terminal one and the status does not. An
unrecognised `type` falls back to the status rather than failing — new error types ship without
a new API revision.

---

## Webhooks

The most important function in this package. A receiver that does not verify will accept a
forged `payment.captured` from anyone who learns the URL; one that verifies the body but
ignores the timestamp will accept a genuine delivery replayed forever.

```ts
import { constructEvent, SIGNATURE_HEADER } from 'paymentflow';

// `body` must be the RAW request bytes. See below.
function handle(body: Buffer, headers: Record<string, string>): void {
  const signature = headers[SIGNATURE_HEADER.toLowerCase()] ?? '';
  const event = constructEvent(body, signature, process.env.WEBHOOK_SECRET ?? '');

  switch (event.type) {
    case 'payment.captured':
      console.log('captured', event.data.object?.['id']);
      break;
    default:
      // New event types ship without a new API revision — ignore what you do not know.
      console.log('ignoring', event.type);
  }
}
```

**The body must be raw.** The signature covers the bytes that were sent, and `JSON.parse`
followed by `JSON.stringify` does not round-trip them — key order, whitespace and number
formatting are all free to change. In Express that means `express.raw({ type: 'application/json' })`
on this route, *before* `express.json()` sees it. This is the single most common way a correct
integration fails.

Three distinct errors, because they are three different operational problems:

```ts
import {
  constructEvent,
  WebhookSignatureError,
  WebhookTimestampError,
  WebhookVerificationError,
} from 'paymentflow';

function verify(body: Buffer, signature: string, secret: string): void {
  try {
    const event = constructEvent(body, signature, secret, 300);
    console.log('verified', event.id);
  } catch (error: unknown) {
    if (error instanceof WebhookTimestampError) {
      // A valid signature arriving late: a replay, or a clock that is wrong.
      console.warn('stale by', error.skewSeconds, 'seconds');
    } else if (error instanceof WebhookSignatureError) {
      // Did not come from PaymentFlow, or did not arrive intact. Do not act on it.
      console.warn('rejected:', error.message);
    } else if (error instanceof WebhookVerificationError) {
      // Authentic, and not an event envelope. A platform problem, not yours.
      console.error(error.message);
    } else {
      throw error;
    }
  }
}
```

The tolerance defaults to **300 seconds** and is the fourth argument, either as a number of
seconds or as `{ toleranceSeconds }`.

**Deduplicate on `event.id`.** Deliveries repeat — after a retry that actually succeeded, after
a manual replay, during a partition — and the id is stable across every one of those.

**Answer 2xx quickly, then do the work.** Anything slower than 5 seconds counts as a failed
attempt and enters the retry schedule.

Verification needs no API key and no client, so a receiver process never has to hold a secret
key it would not otherwise need. `client.webhooks.constructEvent` is the same function if you
prefer to reach it through a client.

To build a signed request in your own tests, use `signatureHeaderFor` rather than
reimplementing the scheme — reimplementing it is the moment you get it subtly wrong and then
write a test that passes against your own mistake:

```ts
import { signatureHeaderFor, constructEvent } from 'paymentflow';

const secret = 'whsec_example';
const body = '{"id":"evt_1","object":"event","type":"payment.captured","data":{"object":{}}}';
const now = Math.floor(Date.now() / 1000);

const header = signatureHeaderFor(secret, now, body);
const event = constructEvent(body, header, secret);
console.log(event.id);
```

A full receiver is in [`examples/05-webhook-receiver.ts`](examples/05-webhook-receiver.ts).

---

## Idempotency and retries

Every mutation the API requires an `Idempotency-Key` for gets one, generated **once per logical
call** and reused across every retry of it. A key regenerated per attempt would make the
platform treat the retry as a new request — and charge the customer twice, under exactly the
network conditions that cause retries.

Supply your own when the retry has to survive your *process* restarting:

```ts
import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY });

await client.payments.create(
  { amountMinor: 1000, currency: 'USD' },
  { idempotencyKey: 'order-A-1234' },
);
```

Retries apply to 429 and 5xx, and to network failures and timeouts — **never** to other 4xx,
which will be rejected identically however many times they are sent. And only to requests that
are safe to replay: `GET`, `DELETE`, or anything carrying an idempotency key. A response that
never arrived does not mean a request that never arrived, so a `POST` the platform does not
deduplicate is raised rather than retried.

Backoff is exponential with full jitter. `Retry-After` overrides it, because that is the
interval the platform will actually accept the request again. `RateLimit-Reset` does not: it
describes the *daily* quota window and appears on successful responses too, so treating it as a
delay would idle a healthy client until midnight UTC. When `Retry-After` exceeds a minute — an
exhausted daily quota, which clears at 00:00 UTC — the SDK stops retrying and raises
`RateLimitError` with `retryAfterSeconds`, so you can schedule the work instead of blocking.

Per-call overrides:

```ts
import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY });

await client.payments.list(
  { limit: 100 },
  { timeout: 60_000, maxRetries: 0, correlationId: 'batch-2026-07-31' },
);
```

---

## Tracing a call

Every response carries `X-Request-Id` and `X-Correlation-Id`, and list results expose them on
`.meta` along with the answering revision and the quota telemetry:

```ts
import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY });

const page = await client.payments.list({ limit: 1 });
console.log(page.meta.requestId, page.meta.apiVersion, page.meta.attempts);
console.log(page.meta.rateLimit?.remaining, 'of', page.meta.rateLimit?.limit);

if (page.meta.deprecated) {
  console.warn('this API revision is superseded');
}
```

`requestId` keys the matching row of `client.requestLogs.list()`, and it is the value to quote
in a support request. Pass your own `correlationId` per call to join a trace that starts in
your system.

---

## TypeScript

The types are half of what this package delivers, so every strictness flag the compiler has is
on. Response models, parameter shapes, page types and the error hierarchy are all exported by
name:

```ts
import {
  PaymentFlow,
  type PaymentResponse,
  type PaymentCreateParams,
  type CursorPage,
  type RequestOptions,
  type WebhookEvent,
} from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY });

async function charge(params: PaymentCreateParams, options?: RequestOptions): Promise<PaymentResponse> {
  return client.payments.create(params, options);
}

async function recent(): Promise<CursorPage<PaymentResponse>> {
  return client.payments.list({ limit: 10 });
}

function summarise(event: WebhookEvent): string {
  return `${event.type} at ${event.created ?? 'unknown'}`;
}

console.log(charge, recent, summarise);
```

Enum-valued fields are deliberately **open** — `'created' | 'authorized' | … | (string & {})`.
The union gives an editor its completions; the intersection keeps the type from closing,
because new values ship without a new API revision and a closed union would make the compiler
reject one. Handle the values you know and ignore the rest.

Response fields are optional because the contract distinguishes an absent field from an
explicitly null one, and `exactOptionalPropertyTypes` keeps the two apart. Client options
accept `undefined` explicitly, so `{ apiKey: process.env.PAYMENTFLOW_API_KEY }` compiles
without a non-null assertion on a credential.

---

## Resources

| Namespace | Methods |
|---|---|
| `payments` | `create` `retrieve` `list` `authorize` `capture` `refund` `void` |
| `refunds` | `retrieve` `list` |
| `balance` | `retrieve` |
| `balanceTransactions` | `list` |
| `events` | `retrieve` `list` |
| `analytics` | `retrievePaymentSummary` |
| `requestLogs` | `list` |
| `usage` | `retrieve` |
| `webhookEndpoints` | `create` `retrieve` `list` `update` `del` `rotateSecret` |
| `webhookDeliveries` | `retrieve` `list` `replay` |
| `testHelpers` | `listCards` `listDecisions` `listDecisionsForPayment` `createSimulationOverride` `retrieveActiveSimulationOverride` `revokeActiveSimulationOverride` |
| `webhooks` | `constructEvent` `signPayload` `signatureHeaderFor` |

`webhookEndpoints.create` and `rotateSecret` are the **only** times the signing secret is
returned. Store it then; there is no way to read it back.

Runnable examples: [`examples/`](examples/).

---

## What is deliberately absent

- **No convenience methods that make two calls.** `createAndCapture` would be two obvious lines
  and a failure mode nobody can reason about, because the second call failing leaves you an
  authorized payment you do not know about.
- **No `mode` option.** The key decides.
- **No request/response hooks.** Not in the approved design, and not added speculatively.
- **No response validation.** Unknown fields and unknown enum values must ride through
  untouched, or the platform's safest kind of change becomes everyone's outage.

---

## Development

```bash
npm ci
npm run verify   # type-check, dual build, tests against dist/, then the examples
```

Tests run against `dist/`, not `src/`. What a user installs is the built output, and a
packaging mistake — a wrong `exports` map, a missing declaration file, a module-system marker
that never got written — is invisible from the source tree and total from a consumer's.

The examples are type-checked against the built `.d.ts` for the same reason, and this README's
own snippets are extracted and compiled by `test/docs.test.mjs`. Documentation that does not
compile is documentation that is wrong.

`src/generated` is written by `./gradlew :sdks:shared:generateSdkSources` from
`docs/openapi.yaml` and is not edited by hand — `./gradlew :sdks:shared:verifySdkSources` fails
the build when what is committed no longer matches the contract. None of it is re-exported
wholesale, and a test asserts that: what an integrator may rely on is this package's decision,
not a code generator's naming.

## Status

Feature-complete for the Node package. Not published to npm; `package.json` is marked `private`,
which `npm publish` refuses.
