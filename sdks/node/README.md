# paymentflow — the PaymentFlow SDK for Node.js

The official client for the PaymentFlow payments API.

> **M22.3.** The client, all eleven resource namespaces, the retry loop, automatic
> idempotency, pagination and the typed error hierarchy are implemented. Webhook signature
> verification is M22.4. Not published; see [Status](#status).

## Requirements

- Node 18 or newer, for the built-in `fetch`. That is the whole reason for the floor: the SDK
  has **zero runtime dependencies**, and a payments SDK that drags in a transitive dependency
  tree is a supply-chain liability for every integrator.
- ESM and CommonJS are both supported. The dual build is plain `tsc` twice plus a ten-line
  script that writes the `{"type": ...}` marker each output directory needs; a bundler would
  have been the largest dependency in the tree.

## Quickstart

```ts
import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env.PAYMENTFLOW_API_KEY });

const payment = await client.payments.create({ amountMinor: 1000, currency: 'USD' });
await client.payments.authorize(payment.id);
await client.payments.capture(payment.id);

for await (const p of await client.payments.list({ status: 'captured' })) {
  console.log(p.id, p.amountMinor);
}
```

## Configuration

| Option | Default | Notes |
|---|---|---|
| `apiKey` | `PAYMENTFLOW_API_KEY` | Required. The key alone decides test vs live mode. |
| `baseUrl` | `https://api.paymentflow.dev` | Override for a local stack. |
| `apiVersion` | the revision this build was generated against | Sent as `PaymentFlow-Version`. |
| `timeout` | 30 000 ms | Per HTTP attempt. |
| `maxRetries` | 3 | Applies only to retryable outcomes. |
| `fetch` | the global one | Injectable, for tests and proxies. |

Anything that cannot work is rejected by the constructor rather than by the first call — an
empty base URL, a negative timeout, an API key with a trailing newline.

## Resources

`payments` · `refunds` · `balance` · `balanceTransactions` · `events` · `analytics` ·
`requestLogs` · `usage` · `webhookEndpoints` · `webhookDeliveries` · `testHelpers`

Every method is exactly one published operation and exactly one HTTP request. There are no
convenience methods that make two calls: a `createAndCapture` would be two obvious lines and a
failure mode nobody can reason about, because the second call failing leaves an authorized
payment the caller does not know they have.

Methods return what the endpoint returns, unwrapped. `payments.refund()` resolves to the
**payment**, because that is what `POST /v1/payments/{id}/refund` responds with.

## Idempotency and retries

Every mutation the contract requires an `Idempotency-Key` for gets one, generated **once per
logical call** and reused across every retry of it. That is the single most important
correctness property here: a key regenerated per attempt makes the platform treat a retry as a
new request, and the customer is charged twice — under exactly the network conditions that
cause retries.

Which operations need a key is read from the generated operation descriptors, not from a list
kept in hand-written code, so it cannot drift from the contract.

Retries apply to 429 and 5xx and to network failures and timeouts, and **only** to requests
that are safe to replay: `GET`, `DELETE`, or anything carrying an idempotency key. A `POST`
the platform does not deduplicate — creating a webhook endpoint, say — is never retried,
because a response that never arrived does not mean a request that never arrived.

Backoff is exponential with full jitter. `Retry-After` overrides it, because that is the
interval the platform will actually accept the request again. `RateLimit-Reset` does **not**:
it describes the *daily* quota window and is present on successful responses too, so treating
it as a delay would idle a healthy client until midnight UTC. When `Retry-After` is longer than
a minute — an exhausted daily quota, which clears at 00:00 UTC — the SDK stops retrying and
raises a `RateLimitError` carrying `retryAfterSeconds`, so the work can be scheduled rather
than blocked on.

## Pagination

List methods return a page that is also an async iterable, so the ordinary thing is already
the paginating thing:

```ts
for await (const refund of await client.refunds.list({ payment: 'pay_1' })) { /* … */ }
```

`.data` (or `.content`), `.hasMore` and `.nextPage()` are there for manual control. Breaking
out of the loop stops making requests.

Two page shapes exist because the API has two: cursor pages on every M19 list, and offset
pages on `webhookDeliveries` and `testHelpers.listDecisions`, which D139 deliberately left on
the older envelope. Both iterate identically.

## Errors

```ts
import { RateLimitError, InvalidRequestError, PaymentFlowError } from 'paymentflow';

try {
  await client.payments.capture(id);
} catch (error) {
  if (error instanceof RateLimitError) scheduleRetry(error.retryAfterSeconds);
  else if (error instanceof InvalidRequestError) reject(error.param, error.message);
  else if (error instanceof PaymentFlowError) report(error.requestId);
  else throw error;
}
```

| Class | Raised for |
|---|---|
| `AuthenticationError` | the key is missing, malformed or unrecognised |
| `PermissionError` | the key is valid and not allowed to do this |
| `InvalidRequestError` | a validation failure, an unknown id, an impossible state change |
| `IdempotencyError` | an `Idempotency-Key` conflict — may succeed later, unlike the above |
| `RateLimitError` | the rate limit or the daily quota |
| `ApiConnectionError` | no response at all: DNS, reset, or the timeout elapsing |
| `ApiError` | the platform failed to handle a request it accepted |

All extend `PaymentFlowError`, so catching that alone is a complete handler. The class is
chosen from `ApiError.type` rather than from the status code, because the platform
distinguishes a retryable 409 from a terminal one and the status does not. An unrecognised
`type` falls back to the status instead of failing — new error types ship without a new API
revision.

Every error carries `code`, `message`, `param`, `fieldErrors`, `requestId`, `correlationId`,
`docUrl`, `statusCode` and `attempts`.

## Tracing a call

Every response carries `X-Request-Id` and `X-Correlation-Id`, and list results expose them on
`.meta` along with the answering API revision and the quota telemetry. `requestId` keys the
matching row of `GET /v1/request_logs`.

That was not true before M22.2: the gateway sent the request id downstream and never returned
it, so a caller could learn it only from an error body. The additive fix is in this milestone.

## What is deliberately absent

- **No `mode` option.** The key decides test or live, and nothing else can. A switch that
  appeared to move a client between modes would be a lie.
- **No request/response hooks.** Not in the approved design, and not added speculatively.
- **No response validation.** Unknown fields and unknown enum values must ride through
  untouched, or the platform's safest kind of change becomes everyone's outage.

## Types

The types are half of what this package delivers, so `tsconfig.json` turns on every strictness
flag the compiler has. `exactOptionalPropertyTypes` in particular is load-bearing: the
contract distinguishes an absent field from an explicitly `null` one, and without that flag
TypeScript would let the two be confused.

Generated enum types stay **open** — `'created' | 'authorized' | … | (string & {})`. The union
gives an editor its completions; the intersection keeps the type from being closed, because
the platform ships new enum values without a new API revision and a closed union would make
the compiler reject one.

## Development

```bash
npm ci
npm run verify     # type-check, dual ESM/CJS build, then tests against the built output
```

Tests run against `dist/`, not `src/`. What a user installs is the built output, and a
packaging mistake — a wrong `exports` map, a missing declaration file, a module-system marker
that never got written — is invisible from the source tree and total from a consumer's.

`src/generated` is written by `./gradlew :sdks:shared:generateSdkSources` from
`docs/openapi.yaml` and is not edited by hand — `./gradlew :sdks:shared:verifySdkSources`
fails the build when what is committed no longer matches the contract. None of it is
re-exported from `src/index.ts`, and a test asserts that: what an integrator may rely on is
this package's decision, not a code generator's naming.

## Status

Not published to npm. `package.json` is marked `private`, which `npm publish` refuses.
Publishing to a public registry is irreversible and effectively claims a public name, so it
needs explicit approval rather than a passing build.
