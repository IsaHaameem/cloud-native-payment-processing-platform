# PaymentFlow Errors

Every non-2xx response from the public `/v1` API has the same shape and carries a code from
the table below. Like [the read-API guide](READ_APIS.md) and the
[webhook guide](../notification-service/docs/WEBHOOKS.md), this file is written for an
integrator rather than for this repository — and every code, status and type in it is
asserted against the running code by `ErrorCatalogueDocumentationConsistencyTest`. Where
this file disagrees with the platform, the test fails and the platform is right.

M25 renders the documentation site; this file is its source for the errors chapter, and the
`docUrl` on every error response points back at it.

---

## 1. The shape

```json
{
  "timestamp": "2026-07-28T12:34:56.789Z",
  "status": 403,
  "type": "permission_error",
  "code": "INSUFFICIENT_SCOPE",
  "message": "This API key does not have the required scope: payments:write",
  "path": "/v1/payments",
  "requestId": "req_9f2c1e7a4b8d",
  "correlationId": "3f9a1c2e-77b4-4a1d-9c3e-1d2b4f6a8c05",
  "docUrl": "https://docs.paymentflow.dev/errors#insufficient_scope"
}
```

| Field | Always present | What it is |
|---|---|---|
| `timestamp` | yes | When the error was produced, UTC. |
| `status` | yes | The HTTP status, repeated in the body so a logged body is self-contained. |
| `type` | yes | The coarse classification. **This is the field to branch on** — see §2. |
| `code` | yes | The specific cause. Stable, but the *set* grows over time — see §3. |
| `message` | yes | Human-readable. Written for a developer reading a log, not for an end user. |
| `param` | no | The offending parameter, when exactly one field failed validation. |
| `path` | yes | The request path. |
| `requestId` | yes | Identifies this one HTTP request. **Quote this in a support request.** |
| `correlationId` | yes | Spans the whole distributed trace, across services. |
| `docUrl` | yes | A link to this code's entry below. |
| `errors` | no | Field-level validation failures, as `[{ "field", "message" }]`. |

Fields that do not apply are **omitted**, not sent as `null`. New fields may be added at any
time without a version change (§4.10) — your client must ignore ones it does not recognise.

---

## 2. Branch on `type`, not on `code`

`type` is a small, closed set. `code` is not: a new code ships whenever the platform learns
to fail in a new way, and under the versioning policy that is an additive change requiring
no version bump. A `switch` over `code` is a `switch` that will one day meet a value it has
never seen.

| `type` | Status range | What it means | Retry? |
|---|---|---|---|
| `authentication_error` | 401 | The credential is missing, malformed, or revoked. | No — fix the key. |
| `permission_error` | 403 | The credential is valid but not allowed to do this. | No — use a key with the right scope. |
| `invalid_request_error` | 400, 404, 409 | The request is wrong, or the resource is not in a state that allows it. | No — not unchanged. |
| `idempotency_error` | 409 | An `Idempotency-Key` was reused with a different request, or a concurrent request holds it. | Sometimes — see the code. |
| `rate_limit_error` | 429 | You are over a limit. | Yes — respect `Retry-After`. |
| `api_error` | 500, 503 | Something failed on our side. You did nothing wrong. | Yes, with backoff. |

The SDKs map these directly onto their exception hierarchy, so in most languages you catch
the type rather than inspecting the field.

There is no `api_connection_error` in this table even though the SDKs raise one. It means the
request never reached us — a DNS failure, a dropped socket — so there is no response for it
to appear in.

---

## 3. The code catalogue

Ordered by status, then alphabetically.

| Code | Status | Type | Meaning |
|---|---|---|---|
| `BAD_REQUEST` | 400 | `invalid_request_error` | The request could not be understood — a malformed body, or a parameter that is not the type it should be. |
| `VALIDATION_FAILED` | 400 | `invalid_request_error` | One or more fields are invalid. `errors` lists every one; `param` names it when there is only one. |
| `UNAUTHORIZED` | 401 | `authentication_error` | No API key, a malformed key, or one that has been revoked or has passed its rotation grace window. |
| `FORBIDDEN` | 403 | `permission_error` | The credential is valid but not permitted here. |
| `INSUFFICIENT_SCOPE` | 403 | `permission_error` | The key does not carry the scope this endpoint needs, or it is a publishable key attempting a write. Publishable keys are read-only by construction. |
| `NOT_FOUND` | 404 | `invalid_request_error` | No such object — **or it belongs to another merchant, or exists in the other mode**. The three are deliberately indistinguishable, so a key cannot be used to probe for data it may not read. |
| `CONFLICT` | 409 | `invalid_request_error` | The resource is not in a state that permits this. Capturing a payment that was already captured, for example. |
| `IDEMPOTENCY_CONFLICT` | 409 | `idempotency_error` | This `Idempotency-Key` was already used with a *different* request body, or a request carrying it is still in flight. The in-flight case resolves on its own and is safe to retry; the reused-key case is not — change the key. |
| `DAILY_QUOTA_EXCEEDED` | 429 | `rate_limit_error` | The daily request quota for this merchant and mode is spent. It resets at 00:00 UTC. Backing off exponentially will not help before then. |
| `RATE_LIMIT_EXCEEDED` | 429 | `rate_limit_error` | The per-key rate limit. Clears in seconds; `Retry-After` and the `RateLimit-*` headers say exactly when. |
| `INTERNAL_ERROR` | 500 | `api_error` | Something failed on our side. Retry with backoff; if it persists, quote the `requestId`. |
| `SERVICE_UNAVAILABLE` | 503 | `api_error` | A dependency is unreachable or the platform is shedding load. Retry with backoff. |

---

## 4. Handling them

**Retry on 429 and 5xx. Never on other 4xx.** Use exponential backoff with full jitter. When
the response carries `Retry-After` or `RateLimit-Reset`, that value wins over whatever your
backoff computed — it is the only one that knows when the limit actually clears.

**Two 409s are not the same.** `CONFLICT` means the operation is not legal against this
resource and retrying will fail identically. `IDEMPOTENCY_CONFLICT` may mean a concurrent
request holds your key, which resolves by itself. This is why they are separate codes with
separate types.

**Two 429s are not the same either.** `RATE_LIMIT_EXCEEDED` clears in seconds;
`DAILY_QUOTA_EXCEEDED` clears at midnight UTC. Exponential backoff against the second wastes
hours.

**A 404 is not proof of absence.** It is also what you get for an object belonging to another
merchant, and for a live object read with a test key. If an object you created is 404ing,
check which key you are using before assuming it was deleted.

**Keep the `requestId`.** It appears in this response, in every log line the request
produced, and on its row in your request log. It is the fastest way to have a specific
failure looked at.
