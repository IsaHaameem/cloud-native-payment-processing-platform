# PaymentFlow Webhooks

The merchant-facing guide (§9.4). This is the document an integrator reads, so it is
written for them rather than for this repository — but it lives beside the code that
implements it, and every value in it is asserted by a test rather than transcribed by
hand. Where a number here disagrees with the platform, the platform is wrong.

M25 renders the documentation site; this file is its source for the webhook chapter.

---

## 1. Register an endpoint

```http
POST /v1/webhook_endpoints
Authorization: Bearer sk_test_...
Content-Type: application/json

{
  "url": "https://your-app.example/webhooks/paymentflow",
  "description": "Production order pipeline",
  "enabledEvents": ["payment.authorized", "payment.captured"],
  "metadata": { "team": "payments", "deploy": "blue" }
}
```

```json
201 Created
{
  "endpoint": {
    "id": "…",
    "url": "https://your-app.example/webhooks/paymentflow",
    "enabled": true,
    "enabledEvents": ["payment.authorized", "payment.captured"],
    "signingSecretPrefix": "whsec_9fK2",
    "apiVersion": "2026-08-01",
    "consecutiveFailureCount": 0,
    "metadata": { "team": "payments", "deploy": "blue" }
  },
  "signingSecret": "whsec_9fK2…"
}
```

**`signingSecret` is shown exactly once.** It is stored encrypted and is never returned
again — every later response shows only `signingSecretPrefix`. If you lose it, rotate
(§7); there is no recovery path, by design.

Requirements: HTTPS only, no embedded credentials, and the URL must be publicly
resolvable — endpoints resolving to private, loopback, link-local, or metadata addresses
are refused (§8). One registration per URL per mode; up to 16 endpoints per mode.

Your key needs the `webhooks:manage` scope. Test-mode and live-mode endpoints are
separate: an `sk_test_` key can only ever see and manage test endpoints, and a test event
can never reach a live endpoint.

### `metadata`

`metadata` is an optional string-to-string map you can attach to an endpoint and read back
on every response. We never interpret it — use it to record which of your systems owns an
endpoint, which deploy registered it, or whatever your own tooling needs to correlate.

It behaves the same way on payments and refunds (see the [read-API
guide](../../docs/READ_APIS.md)), with one difference: endpoint metadata is **not
filterable**. The endpoint list always returns every endpoint you have in a mode, and that
set is capped at 16, so there is nothing to filter down.

`PATCH` replaces `metadata` wholesale rather than merging it, so send the complete map you
want stored. Omitting the field leaves what is there untouched; sending `{}` clears it. An
endpoint you never gave metadata reports `{}`, never `null`.

### Event types

`payment.created`, `payment.authorized`, `payment.captured`, `payment.failed`,
`payment.voided`, `payment.refunded`, `payment.partially_refunded`.

`"*"` subscribes to everything, **including event types added later**. New event types are
additive and are not a breaking change, so your handler must tolerate ones it does not
recognise — ignore them rather than erroring.

---

## 2. Verify the signature

Every delivery carries:

```
PaymentFlow-Signature: t=1785758400,v1=5f2c…9ab
```

```
signed_payload = "{t}" + "." + "{the raw request body, byte for byte}"
v1             = lowercase hex HMAC-SHA256(your whsec_ secret, signed_payload)
```

The `whsec_` prefix **is part of the key** — do not strip it before computing the HMAC.

Compute the HMAC over the body exactly as received. Do not parse and re-serialize the
JSON first: any change in key order or whitespace produces a different signature, and most
JSON libraries will make one.

**Node**

```js
const crypto = require('crypto');

function verify(rawBody, header, secret, toleranceSeconds = 300) {
  const parts = Object.fromEntries(header.split(',').map((p) => p.trim().split('=')));
  const timestamp = Number(parts.t);
  if (Math.abs(Date.now() / 1000 - timestamp) > toleranceSeconds) return false;

  const expected = crypto
    .createHmac('sha256', secret)
    .update(`${timestamp}.${rawBody}`)
    .digest('hex');

  const received = header.split(',').filter((p) => p.trim().startsWith('v1='))
    .map((p) => p.trim().slice(3));
  return received.some((sig) =>
    sig.length === expected.length &&
    crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected)));
}
```

**Python**

```python
import hashlib, hmac, time

def verify(raw_body: str, header: str, secret: str, tolerance: int = 300) -> bool:
    parts = dict(p.strip().split("=", 1) for p in header.split(","))
    timestamp = int(parts["t"])
    if abs(time.time() - timestamp) > tolerance:
        return False

    expected = hmac.new(
        secret.encode(), f"{timestamp}.{raw_body}".encode(), hashlib.sha256
    ).hexdigest()

    received = [p.strip()[3:] for p in header.split(",") if p.strip().startswith("v1=")]
    return any(hmac.compare_digest(expected, sig) for sig in received)
```

Both implementations, and the platform's own, are checked against the same committed test
vectors (`src/test/resources/signature-vectors/`). If you write a third, use those vectors.

---

## 3. Enforce the timestamp tolerance

**This is the step most implementations skip, and skipping it defeats the signature.**

A signature over the body alone is valid forever: anyone who ever observes one delivery
can resend it, unchanged, at any point in the future and it will still verify. The
timestamp is inside the signed payload precisely so that it cannot be edited — which makes
a tolerance window enforceable, and a replay detectable.

Reject any delivery whose `t` is outside your window. 300 seconds is a reasonable default.
Compare the absolute difference: a timestamp far in the *future* is equally a sign the
header did not come from us for this delivery.

---

## 4. Respond 2xx quickly; do the work afterwards

Return `2xx` as soon as you have durably recorded the event, then process
asynchronously. Any non-2xx, or no response within **5 seconds**, counts as a failed
attempt and enters the retry schedule.

Redirects are not followed — a `3xx` is a failure, not a hop.

Response bodies are read up to 8 KB and stored in your delivery log; anything beyond that
is discarded.

---

## 5. Be idempotent on `event.id`

Events may arrive more than once — after a retry that actually succeeded the first time,
after a manual replay, or during a network partition. The event's `id` (`evt_…`) is stable
across every one of those: record it and ignore duplicates.

You can prove your handler is idempotent without waiting for a real duplicate. In test
mode:

```http
POST /v1/test/simulations
{ "scenario": "DUPLICATE_WEBHOOKS", "remainingCount": 5 }
```

Every delivery is then sent twice, both visible in your delivery log.

---

## 6. Understand the retry schedule and auto-disable

A failed delivery is retried on a fixed, published schedule — **8 attempts over roughly 19
hours**:

| Attempt | Delay after the previous |
|---|---|
| 1 | immediate |
| 2 | 5 seconds |
| 3 | 30 seconds |
| 4 | 2 minutes |
| 5 | 10 minutes |
| 6 | 1 hour |
| 7 | 6 hours |
| 8 | 12 hours |

After the eighth attempt the delivery is dead-lettered and no further attempt is made.

After **20 consecutive failures across distinct events**, the endpoint is disabled and we
email you. Events are **not** queued while an endpoint is disabled — they are not delivered
at all. Re-enable with `PATCH /v1/webhook_endpoints/{id}` and `{"enabled": true}`, which
also resets the failure counter. A single success at any point resets it too, so an
occasionally-flaky endpoint is never disabled.

To exercise this without breaking your own endpoint:

```http
POST /v1/test/simulations
{ "scenario": "WEBHOOK_FAILURE", "durationSeconds": 300 }
```

---

## 7. Rotate secrets with the dual-secret window

```http
POST /v1/webhook_endpoints/{id}/rotate_secret
```

Returns a new `signingSecret`, once. For the next **48 hours** deliveries are signed with
**both** the new secret and the old one, and the header carries two `v1` values:

```
PaymentFlow-Signature: t=1785758400,v1=<new>,v1=<old>
```

Accept a delivery if **any** `v1` verifies — the snippets in §2 already do. That is what
lets you deploy the new secret across your fleet at your own pace without dropping
deliveries mid-rollout. After 48 hours the old secret stops being used.

---

## 8. What we will not connect to

Webhook delivery is an outbound request to an address you choose, so it is egress-filtered.
Refused before any connection is made, and recorded in your delivery log as `BLOCKED`
(distinct from a connection failure — we want you to know we never tried):

- non-HTTP(S) schemes
- loopback (`127.0.0.0/8`, `::1`) and `0.0.0.0`
- link-local (`169.254.0.0/16`, `fe80::/10`), including cloud metadata addresses
- private ranges (`10/8`, `172.16/12`, `192.168/16`, `fc00::/7`)
- carrier-grade NAT (`100.64.0.0/10`) and multicast
- the same addresses expressed as IPv4-mapped IPv6 (`::ffff:127.0.0.1`)
- URLs with embedded credentials

Every address a hostname resolves to is checked, not just the first, and the connection is
made to the addresses that were checked.

---

## 9. Debug with the delivery log and replay

```http
GET  /v1/webhook_deliveries
GET  /v1/webhook_deliveries/{id}
POST /v1/webhook_deliveries/{id}/replay
```

Each delivery lists every attempt with the request URL, the headers we sent (including the
signature), the exact body, your response status, headers and body, the duration, and any
transport error.

Replay creates a **new** delivery with its own attempts. The original is never modified, so
your record of what happened the first time stays intact. The replay is re-signed with a
current timestamp, so it passes the tolerance window in §3.

---

## 10. Test mode and live mode

Endpoints, secrets, events, and delivery logs are completely separate between modes. A
test event can never reach a live endpoint. Simulation controls (§5, §6) are test-mode
only.
