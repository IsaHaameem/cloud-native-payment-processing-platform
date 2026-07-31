# paymentflow — the PaymentFlow SDK for Python

The official client for the PaymentFlow payments API. Fully typed, `py.typed`, one runtime
dependency.

> **Not published.** `pyproject.toml` carries `Private :: Do Not Upload`, which PyPI refuses.
> Publishing to a public index is irreversible and effectively claims a name, so it needs an
> explicit decision rather than a passing build. See [Status](#status).

This is the Python half of a pair. The [Node SDK](../node/README.md) is the same client in
another language — same options, same retry rules, same error hierarchy, same pagination — and
a test in `sdks/shared` compares the two so the promise stays true.

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
- [Typing](#typing)
- [Resources](#resources)
- [Differences from the Node SDK](#differences-from-the-node-sdk)
- [What is deliberately absent](#what-is-deliberately-absent)
- [Development](#development)
- [Status](#status)

---

## Installation

```bash
pip install paymentflow
```

Python 3.9 or newer. Deliberately not the newest: an SDK runs in its users' environments, not
in ours. The only runtime dependency is [`httpx`](https://www.python-httpx.org/) — a payments
SDK that drags in a transitive tree is a supply-chain liability for every integrator, so the
dependency list is a design constraint rather than a preference.

---

## Authentication

Your secret API key, as `Authorization: Bearer sk_…`, on every request. The SDK does this for
you; you only supply the key.

**The key alone decides the mode.** An `sk_test_` key reads and writes test data, an `sk_live_`
key live data, and nothing else — no header, no option, no body field — can change that. That
is why this SDK has no `mode` option and will not get one: a switch that appeared to move a
client between modes would be a lie.

---

## Creating a client

```python
from paymentflow import PaymentFlow

client = PaymentFlow()          # reads PAYMENTFLOW_API_KEY
client = PaymentFlow(api_key="sk_test_…")
```

| Option | Default | Notes |
|---|---|---|
| `api_key` | `PAYMENTFLOW_API_KEY` | Required, one way or the other. |
| `base_url` | `https://api.paymentflow.dev` | Override for a local stack. |
| `api_version` | the revision this build was generated against | Sent as `PaymentFlow-Version`. |
| `timeout` | `30.0` | **Seconds**, per HTTP attempt. |
| `max_retries` | `3` | Applies only to retryable outcomes. |
| `http_client` | a new `httpx.Client` | Injectable, for tests and proxies. |

Anything that cannot work is rejected by the constructor rather than by the first call — an
invalid base URL, a negative timeout, an API key with a trailing newline out of a `.env` file.
A credential problem should surface on the line that configured it, not hours later on a
payment.

A client owns an `httpx.Client`, so build one per API key and share it. Use it as a context
manager to close the connection pool:

```python
with PaymentFlow() as client:
    client.payments.retrieve("pay_1")
```

An `http_client` you pass in is yours — the SDK never closes it.

---

## Payments

Every method is exactly one published operation and exactly one HTTP request.

```python
# Amounts are integers in the currency's minor unit. 1000 in USD is $10.00 —
# there are no floating-point amounts anywhere in this API.
payment = client.payments.create(
    amount_minor=1000,
    currency="USD",
    description="Order A-1234",
    metadata={"orderId": "A-1234"},
)

client.payments.retrieve(payment["id"])
client.payments.authorize(payment["id"])
client.payments.capture(payment["id"])

# Voiding releases an authorization instead of capturing it.
other = client.payments.create(amount_minor=800, currency="USD")
client.payments.authorize(other["id"])
client.payments.void(other["id"])
```

In test mode, pass a seeded card token to choose the outcome:

```python
for card in client.test_helpers.list_cards():
    print(card["token"], card["description"])

client.payments.create(amount_minor=2000, currency="USD", payment_method_token="tok_visa_approved")
```

---

## Refunds

A refund is created by refunding a **payment**, which is where the API puts it — so
`payments.refund()` is the only way to make one, and it returns the payment. The new refund is
in `payment["refunds"]`.

```python
# Omit `amount_minor` to refund everything still refundable.
payment = client.payments.refund("pay_1", amount_minor=500, reason="requested_by_customer")
print(payment["refundedAmountMinor"], "of", payment["amountMinor"])

# `client.refunds` reads them back.
refund = client.refunds.retrieve("re_1")
for each in client.refunds.list(payment="pay_1"):
    print(each["id"], each["amountMinor"])
```

---

## Pagination

List methods return a page that is **also iterable**, so the ordinary thing is already the
paginating thing:

```python
total = 0
for payment in client.payments.list(status="captured"):
    total += payment.get("amountMinor", 0)
```

It fetches pages as it goes and holds one at a time, so that is safe on an account of any size.
`break` stops making requests.

For manual control, the same object exposes the page directly:

```python
page = client.payments.list(limit=50)
while page is not None:
    print(len(page.data), "on this page;", "more follow" if page.has_more else "that is all")
    page = page.next_page()
```

Filters carry across pages automatically — re-issuing a page request with different filters
than the cursor was minted under returns a result set that never existed. `metadata` is a
containment filter where every named key must match:

```python
for payment in client.payments.list(
    metadata={"orderId": "A-1234", "channel": "web"},
    created_after="2026-01-01T00:00:00Z",
):
    print(payment["id"])
```

**Two page shapes**, because the API has two. Cursor pages (`data`, `has_more`, `next_cursor`)
on most lists; offset pages (`content`, `page`, `size`, `total_elements`, `total_pages`) on
`webhook_deliveries` and `test_helpers.list_decisions`. Both iterate identically; the offset
ones additionally report totals, which a cursor page deliberately does not.

```python
deliveries = client.webhook_deliveries.list(size=20, sort=["createdAt,desc"])
print(deliveries.total_elements, "deliveries across", deliveries.total_pages, "pages")

for delivery in deliveries:
    print(delivery["id"], delivery["status"])
```

Two lists are **not** paginated on the wire and return plain lists:
`client.webhook_endpoints.list()` (capped at 16 per mode) and `client.test_helpers.list_cards()`.
Wrapping them in a page would invent a `has_more` no response carries.

---

## Errors

Catching `PaymentFlowError` is already a complete handler. Narrow from there when you can do
something more useful than log.

```python
from paymentflow import (
    PaymentFlowError, RateLimitError, InvalidRequestError,
    IdempotencyError, ApiConnectionError,
)

try:
    client.payments.capture("pay_1")
except RateLimitError as error:
    # The SDK already waited out anything short; reaching here means the interval was
    # longer than it will block for. Schedule rather than retry now.
    print("retry in", error.retry_after_seconds, "seconds")
except IdempotencyError as error:
    # A concurrent request holds the same key. This one may succeed later.
    print("conflict:", error.code)
except InvalidRequestError as error:
    # Will be rejected identically however many times it is sent.
    print(error.code, error.message, error.param)
    for field in error.field_errors:
        print(field["field"], field["message"])
except ApiConnectionError as error:
    # No response at all, so whether it took effect is genuinely unknown — which is
    # exactly why mutations carry an idempotency key.
    print("no response after", error.attempts, "attempts")
except PaymentFlowError as error:
    print(type(error).__name__, error.message, "request", error.request_id)
```

| Class | Raised for |
|---|---|
| `AuthenticationError` | the key is missing, malformed or unrecognised |
| `PermissionDeniedError` | the key is valid and not allowed to do this |
| `InvalidRequestError` | a validation failure, an unknown id, an impossible state change |
| `IdempotencyError` | an `Idempotency-Key` conflict — may succeed later, unlike the above |
| `RateLimitError` | the rate limit or the daily quota; carries `retry_after_seconds` |
| `ApiConnectionError` | no response at all: DNS, reset, or the timeout elapsing |
| `ApiError` | the platform failed to handle a request it accepted |

All inherit `PaymentFlowError` and carry `code`, `message`, `param`, `field_errors`,
`request_id`, `correlation_id`, `doc_url`, `status_code` and `attempts`.

The class is chosen from the response's `type` field, not from the status code, because the
platform distinguishes a retryable 409 from a terminal one and the status does not. An
unrecognised `type` falls back to the status rather than failing — new error types ship without
a new API revision.

---

## Webhooks

The most important function in this package. A receiver that does not verify will accept a
forged `payment.captured` from anyone who learns the URL; one that verifies the body but
ignores the timestamp will accept a genuine delivery replayed forever.

```python
from paymentflow import construct_event, SIGNATURE_HEADER

def handle(raw_body: bytes, headers: dict) -> None:
    event = construct_event(raw_body, headers[SIGNATURE_HEADER.lower()], WEBHOOK_SECRET)

    if event["type"] == "payment.captured":
        print("captured", event["data"]["object"]["id"])
    else:
        # New event types ship without a new API revision — ignore what you do not know.
        print("ignoring", event["type"])
```

**The body must be raw.** The signature covers the bytes that were sent, and `json.loads`
followed by `json.dumps` does not round-trip them — key order, whitespace and number formatting
are all free to change. In Flask that means `request.get_data()`, not `request.get_json()`; in
FastAPI, `await request.body()`. This is the single most common way a correct integration
fails.

Three distinct errors, because they are three different operational problems:

```python
from paymentflow import (
    construct_event, WebhookSignatureError, WebhookTimestampError, WebhookVerificationError,
)

try:
    event = construct_event(raw_body, signature, secret, 300)
except WebhookTimestampError as error:
    # A valid signature arriving late: a replay, or a clock that is wrong.
    print("stale by", error.skew_seconds, "seconds")
except WebhookSignatureError as error:
    # Did not come from PaymentFlow, or did not arrive intact. Do not act on it.
    print("rejected:", error)
except WebhookVerificationError as error:
    # Authentic, and not an event envelope. A platform problem, not yours.
    print(error)
```

The tolerance defaults to **300 seconds** and is the fourth argument.

**Deduplicate on `event["id"]`.** Deliveries repeat — after a retry that actually succeeded,
after a manual replay, during a partition — and the id is stable across every one of those.

**Answer 2xx quickly, then do the work.** Anything slower than 5 seconds counts as a failed
attempt and enters the retry schedule.

Verification needs no API key and no client, so a receiver process never has to hold a secret
key it would not otherwise need. `client.webhooks.construct_event` is the same function.

To build a signed request in your own tests, use `signature_header_for` rather than
reimplementing the scheme — reimplementing it is the moment you get it subtly wrong and then
write a test that passes against your own mistake:

```python
from paymentflow import signature_header_for, construct_event
import time

header = signature_header_for(secret, int(time.time()), body)
event = construct_event(body, header, secret)
```

A full receiver is in [`examples/05_webhook_receiver.py`](examples/05_webhook_receiver.py).

---

## Idempotency and retries

Every mutation the API requires an `Idempotency-Key` for gets one, generated **once per logical
call** and reused across every retry of it. A key regenerated per attempt would make the
platform treat the retry as a new request — and charge the customer twice, under exactly the
network conditions that cause retries.

Supply your own when the retry has to survive your *process* restarting:

```python
from paymentflow import RequestOptions

client.payments.create(
    amount_minor=1000,
    currency="USD",
    options=RequestOptions(idempotency_key="order-A-1234"),
)
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
`RateLimitError` with `retry_after_seconds`, so you can schedule the work instead of blocking.

Per-call overrides:

```python
client.payments.list(
    limit=100,
    options=RequestOptions(timeout=60.0, max_retries=0, correlation_id="batch-2026-07-31"),
)
```

---

## Tracing a call

Every response carries `X-Request-Id` and `X-Correlation-Id`, and list results expose them on
`.meta` along with the answering revision and the quota telemetry:

```python
page = client.payments.list(limit=1)
print(page.meta.request_id, page.meta.api_version, page.meta.attempts)
if page.meta.rate_limit is not None:
    print(page.meta.rate_limit.remaining, "of", page.meta.rate_limit.limit)
if page.meta.deprecated:
    print("this API revision is superseded")
```

`request_id` keys the matching row of `client.request_logs.list()`, and it is the value to
quote in a support request. Pass your own `correlation_id` per call to join a trace that starts
in your system.

---

## Typing

The package ships `py.typed`, so your type checker uses these annotations rather than treating
the SDK as `Any`. Responses are `TypedDict`s with the API's own field names:

```python
from paymentflow import PaymentFlow, PaymentResponse, CursorPage, RequestOptions

def charge(client: PaymentFlow, amount: int) -> PaymentResponse:
    return client.payments.create(amount_minor=amount, currency="USD")

def recent(client: PaymentFlow) -> CursorPage[PaymentResponse]:
    return client.payments.list(limit=10)
```

`TypedDict` rather than dataclasses, deliberately: a dataclass constructor rejecting an unknown
keyword would break every integrator the first time the platform added a field, which is
exactly the change the API promises is safe. Every field is optional for the same reason, and
enum-valued fields are plain `str` — the documented values are exported as `*_VALUES` tuples
inside the generated module, to recognise against and never to validate with.

**You send Python names and read the API's.** `create(amount_minor=…)` returns
`payment["amountMinor"]`. The asymmetry is deliberate: keyword arguments are how a Python API
is spelled, while the response models are generated from the contract and carry its field names
— translating those would mean a second source of truth for every field in the platform.

---

## Resources

| Namespace | Methods |
|---|---|
| `payments` | `create` `retrieve` `list` `authorize` `capture` `refund` `void` |
| `refunds` | `retrieve` `list` |
| `balance` | `retrieve` |
| `balance_transactions` | `list` |
| `events` | `retrieve` `list` |
| `analytics` | `retrieve_payment_summary` |
| `request_logs` | `list` |
| `usage` | `retrieve` |
| `webhook_endpoints` | `create` `retrieve` `list` `update` `delete` `rotate_secret` |
| `webhook_deliveries` | `retrieve` `list` `replay` |
| `test_helpers` | `list_cards` `list_decisions` `list_decisions_for_payment` `create_simulation_override` `retrieve_active_simulation_override` `revoke_active_simulation_override` |
| `webhooks` | `construct_event` `sign_payload` `signature_header_for` |

`webhook_endpoints.create` and `rotate_secret` are the **only** times the signing secret is
returned. Store it then; there is no way to read it back.

Runnable examples: [`examples/`](examples/).

---

## Differences from the Node SDK

The two are the same client, and three things are spelled differently because Python forces it:

| | Node | Python |
|---|---|---|
| Permission failure | `PermissionError` | `PermissionDeniedError` — the first is a Python builtin, and shadowing it would silently stop a module catching filesystem errors |
| Deleting an endpoint | `del()` | `delete()` — `del` is a Python keyword |
| Timeouts | milliseconds | seconds — each language's ecosystem convention |
| Analytics window | `from` | `from_` — `from` is a Python keyword; it is sent as `from` |

Everything else — option names, defaults, retry rules, backoff constants, tolerance windows,
error classification, page shapes — is identical, and `SdkParityTest` in `sdks/shared` fails
the build if it stops being.

---

## What is deliberately absent

- **No async client yet.** §7.2 sequences it after the sync one; see [Status](#status).
- **No convenience methods that make two calls.** `create_and_capture` would be two obvious
  lines and a failure mode nobody can reason about, because the second call failing leaves you
  an authorized payment you do not know about.
- **No `mode` option.** The key decides.
- **No response validation.** Unknown fields and unknown enum values must ride through
  untouched, or the platform's safest kind of change becomes everyone's outage.

---

## Development

```bash
pip install -e ".[dev]"
mypy && pytest
```

`mypy` runs `--strict` over the package *and* its tests. `py.typed` tells a consumer's type
checker to trust these annotations; shipping annotations nobody checked would make that marker
a false promise.

`tests/test_python_floor.py` parses every shipped module against the grammar of the oldest
Python this package supports, because mypy cannot model 3.9 and the risk at the floor is syntax
and runtime API rather than types.

`src/paymentflow/_generated` is written by `./gradlew :sdks:shared:generateSdkSources` from
`docs/openapi.yaml` and is not edited by hand — `./gradlew :sdks:shared:verifySdkSources` fails
the build when what is committed no longer matches the contract. None of it is re-exported
wholesale, and a test asserts that: what an integrator may rely on is this package's decision,
not a code generator's naming.

## Status

Feature-complete for the synchronous client. The **async variant §7.2 calls for is not built**
— `async`/`await` colours every function it touches, so it means a second transport and a
second copy of all eleven resource namespaces, which deserves its own sub-milestone rather than
being rushed in beside the first. Not published to PyPI.
