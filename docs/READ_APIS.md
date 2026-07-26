# PaymentFlow Read APIs

The merchant-facing guide to everything you can *read* from PaymentFlow (§9). Like the
[webhook guide](../notification-service/docs/WEBHOOKS.md), this is written for an
integrator rather than for this repository — but it lives beside the code that implements
it, and every number in it is asserted by a test rather than transcribed by hand. Where a
value here disagrees with the platform, the platform is wrong.

M25 renders the documentation site; this file is its source for the read-API chapter.

---

## 1. The surface

| Resource | Endpoints | Scope your key needs |
|---|---|---|
| Payments | `GET /v1/payments`, `GET /v1/payments/{id}` | `payments:read` |
| Refunds | `GET /v1/refunds`, `GET /v1/refunds/{id}` | `payments:read` |
| Balance | `GET /v1/balance`, `GET /v1/balance_transactions` | `balance:read` |
| Events | `GET /v1/events`, `GET /v1/events/{id}` | `events:read` |
| Analytics | `GET /v1/analytics/payments` | `analytics:read` |

Refunds deliberately reuse `payments:read` rather than getting a scope of their own: a key
that may read payments may read their refunds. `refunds:write` describes *issuing* one,
which is `payments:write` on the payment itself (`POST /v1/payments/{id}/refund`).

There is no write endpoint anywhere in this guide. Balance, events, and analytics are
served by three services that have no write API at all — their data is produced by
consuming the platform's own event stream, and that is the only way anything gets into
them.

---

## 2. Authentication and mode

Every request needs a secret key:

```http
GET /v1/payments HTTP/1.1
Host: api.paymentflow.dev
Authorization: Bearer sk_test_...
```

**Mode is bound to the key, not to the request.** An `sk_test_` key sees test data and
only test data; an `sk_live_` key sees live data and only live data. There is no header,
parameter, or body field that changes this — the platform strips any attempt to set one
before your request reaches a service. If you want to read the other mode, use the other
key.

The same applies to *whose* data you are reading: the merchant is resolved from your key.
No endpoint in this guide takes a merchant id, so there is no request you can construct
that names someone else's data.

---

## 3. Lists and cursor pagination

Every list endpoint returns the same envelope:

```json
{
  "object": "list",
  "data": [ … ],
  "hasMore": true,
  "nextCursor": "eyJ…"
}
```

To page, pass `nextCursor` back as `starting_after`:

```http
GET /v1/payments?limit=50
GET /v1/payments?limit=50&starting_after=eyJ…
```

When `hasMore` is `false`, `nextCursor` is absent and you are done.

| Parameter | Default | Notes |
|---|---|---|
| `limit` | 25 | Clamped to a maximum of 100 — asking for more returns 100, not an error |
| `starting_after` | — | An opaque cursor from a previous response |

**There is no total count, and no page number.** A cursor names a *position in your data*,
not a number of rows to skip. That is what makes it correct: with offset pagination, a
payment created while you are paging shifts every later row down by one, so page 2 repeats
something page 1 already gave you and something else falls through the gap entirely. For a
financial list that is a correctness bug, not a cosmetic one. Cursors do not have it.

Reporting a total would mean counting your entire history on every request, which is the
one thing a list endpoint over an ever-growing table must not do. `hasMore` answers the
only question a paginating client actually has.

**Treat the cursor as opaque.** It is signed, and the platform verifies it: an edited,
truncated, or hand-made cursor is rejected with `400`, and a cursor issued for a different
key's merchant or mode is rejected outright rather than quietly returning an empty page.
Do not parse it, construct one, or store one expecting it to mean anything later.

Lists are ordered **newest first** by creation time, with the object id breaking ties. Two
objects created in the same millisecond are still returned in a stable, total order — which
is why a cursor is a position rather than a timestamp.

### Time ranges

Every list accepts `created_after` and `created_before` as ISO-8601 instants:

```http
GET /v1/payments?created_after=2026-07-01T00:00:00Z&created_before=2026-08-01T00:00:00Z
```

`created_after` is **inclusive**, `created_before` is **exclusive**. That makes adjacent
windows tile without overlapping, so you can walk a month at a time and never see the same
object twice. `created_after` must be strictly earlier than `created_before`, or you get a
`400`.

---

## 4. Filtering

### Payments

| Parameter | Example | Notes |
|---|---|---|
| `status` | `status=CAPTURED` | One of `CREATED`, `AUTHORIZED`, `CAPTURED`, `PARTIALLY_REFUNDED`, `REFUNDED`, `FAILED`, `VOIDED` |
| `currency` | `currency=USD` | Three-letter code, case-insensitive |
| `amount_min` / `amount_max` | `amount_min=1000` | Minor units, both inclusive |
| `created_after` / `created_before` | see §3 | |
| `metadata` | see §5 | |

### Refunds

| Parameter | Example | Notes |
|---|---|---|
| `payment` | `payment=<payment id>` | Every refund of one payment |
| `status` | `status=SUCCEEDED` | `SUCCEEDED` or `FAILED` |
| `created_after` / `created_before` | see §3 | |
| `metadata` | see §5 | |

Filters combine with AND. Everything you can filter on is indexed, so narrowing a list
makes it faster rather than slower.

**A filter value we do not recognise is a `400`, not an empty list.** `status=AUTHORISED`
returns an error naming the mistake, because silently returning zero payments for a typo is
the kind of answer that costs an afternoon. The error message lists the accepted values.

---

## 5. `metadata`

`metadata` is a string-to-string map you attach to a payment, a refund, or a
[webhook endpoint](../notification-service/docs/WEBHOOKS.md). We never interpret it. Put
your own order id, customer reference, or internal ticket in it and read it back.

Set it when you create the object:

```json
{ "amountMinor": 4200, "currency": "USD", "metadata": { "orderId": "A-1234" } }
```

Filter payments and refunds by it:

```http
GET /v1/payments?metadata[orderId]=A-1234
```

Percent-encoding the brackets (`metadata%5BorderId%5D=A-1234`) is equivalent — most HTTP
clients do it for you, and both spellings behave identically.

Matching is **containment**: the object's metadata must contain every key/value pair you
name, and may contain more. Naming two keys requires both to match. An object with no
metadata matches nothing — `{}` contains no keys, so it cannot contain yours.

An object you never gave metadata reports `{}`, never `null`. You do not have to handle
both.

Metadata is annotation, not state. Changing it never moves a payment through its lifecycle
and never emits an event.

---

## 6. `expand`

A payment's refunds can be fetched with it, on both the list and the single read:

```http
GET /v1/payments/{id}?expand=refunds
GET /v1/payments?expand=refunds
```

```json
{
  "id": "…",
  "object": "payment",
  "status": "PARTIALLY_REFUNDED",
  "amountMinor": 4200,
  "refundedAmountMinor": 1000,
  "refunds": [
    { "id": "…", "object": "refund", "amountMinor": 1000, "status": "SUCCEEDED", … }
  ]
}
```

Expanding a list costs one extra query for the whole page, not one per payment.

`refunds` is the only expandable relation, it cannot itself be expanded, and there is no
nesting — so there is no depth to limit. **An `expand` value we do not recognise is
ignored, not rejected**, so an SDK written against a later version does not break against
an older one.

Without `expand`, the `refunds` field is **absent** rather than `[]` — "you did not ask" is
a different statement from "this payment has none". `refundedAmountMinor` is always
present either way.

> Payments refunded before refunds became first-class objects have a
> `refundedAmountMinor` but no refund objects behind it. Only the running total was ever
> recorded, and inventing one refund per historical total would mean fabricating an id and
> a timestamp that never existed. The total is accurate; the history starts when the
> objects do.

---

## 7. Errors

| Status | When |
|---|---|
| `400` | A malformed parameter: an unknown `status`, a bad cursor, an inverted date range, `limit=0`, a window over the cap |
| `401` | Missing, malformed, or revoked key |
| `403` | A valid key without the scope this endpoint needs |
| `404` | The object does not exist **or** is not yours **or** is in the other mode |

That last row is deliberate and worth reading twice. Asking for an object belonging to
another merchant returns `404`, not `403` — a `403` would confirm the object exists, which
is exactly what you should not be able to learn. The same applies across modes: a live
payment's id, presented with a test key, is simply not found.

So a `404` means "not visible to this key". It does not tell you which of the three
reasons applies, and that is the point.

---

## 8. Payments

```http
GET /v1/payments/{id}
```

```json
{
  "id": "…",
  "object": "payment",
  "merchantId": "…",
  "mode": "test",
  "amountMinor": 4200,
  "currency": "USD",
  "status": "CAPTURED",
  "capturedAmountMinor": 4200,
  "refundedAmountMinor": 0,
  "description": "Order A-1234",
  "paymentMethodToken": "pm_card_visa",
  "metadata": { "orderId": "A-1234" },
  "createdAt": "2026-07-25T10:31:04.221Z",
  "updatedAt": "2026-07-25T10:31:06.918Z"
}
```

All amounts everywhere in this API are **minor units** of the payment's currency — cents
for `USD`, pence for `GBP`. There are no decimal amounts and no floating-point money.

`failureReason` appears only on a `FAILED` payment.

---

## 9. Refunds

A refund is its own object with its own id, created by
`POST /v1/payments/{id}/refund`:

```http
GET /v1/refunds?payment=<payment id>
```

```json
{
  "id": "…",
  "object": "refund",
  "paymentId": "…",
  "merchantId": "…",
  "mode": "test",
  "amountMinor": 1000,
  "currency": "USD",
  "status": "SUCCEEDED",
  "reason": "Customer returned item",
  "metadata": {},
  "createdAt": "2026-07-25T11:02:44.010Z",
  "updatedAt": "2026-07-25T11:02:44.010Z"
}
```

`failureReason` is present exactly when `status` is `FAILED`, and absent otherwise — the
database will not store a row that says otherwise.

The refunds *list* is newest-first like every other list. The refunds *inside*
`expand=refunds` are oldest-first, because there they read as one payment's history rather
than as a feed.

---

## 10. Balance and balance transactions

```http
GET /v1/balance
```

```json
{
  "object": "balance",
  "balances": [
    { "currency": "USD", "pendingMinor": 4200, "availableMinor": 15800 }
  ]
}
```

- **`pendingMinor`** — authorized but not yet captured.
- **`availableMinor`** — captured and owed to you.

These are two different accounts in a double-entry ledger, not one number split in two. A
merchant with no activity gets an empty `balances` list, not a `404` — having no balance is
a fact about you, not a missing resource.

```http
GET /v1/balance_transactions
```

```json
{
  "object": "list",
  "data": [
    {
      "id": "…",
      "object": "balance_transaction",
      "paymentId": "…",
      "eventType": "PaymentCaptured",
      "accountType": "MERCHANT_SETTLED",
      "direction": "CREDIT",
      "amountMinor": 4200,
      "currency": "USD",
      "mode": "test",
      "createdAt": "2026-07-25T10:31:06.918Z"
    }
  ],
  "hasMore": false
}
```

Each row is one leg against one of *your* accounts, with the payment and lifecycle event
that caused it. The balancing leg touches the platform's own clearing account, which is
not part of your ledger and never appears here.

Your balance is **projected from these entries**, not stored beside them. Summing the
entries for a currency reproduces the balance exactly, by construction rather than by
reconciliation.

Filterable by `created_after` / `created_before`, and paginated like every other list.

---

## 11. Events

Every webhook we send you is also readable here, in the same canonical shape and under the
same id — so an `evt_…` from a webhook body can be handed straight back:

```http
GET /v1/events/{id}
GET /v1/events?type=payment.captured
```

```json
{
  "id": "evt_4f2c1a9b7e0d4c5a8b3f6d2e9c1a0b7e",
  "object": "event",
  "type": "payment.captured",
  "mode": "test",
  "created": "2026-07-25T10:31:06.918Z",
  "data": { … }
}
```

Event types: `payment.created`, `payment.authorized`, `payment.captured`,
`payment.failed`, `payment.voided`, `payment.refunded`, `payment.partially_refunded`.

An unknown `type` is a `400` naming the vocabulary, for the same reason an unknown `status`
is (§4).

**Ordered by when things happened**, not by when we recorded them. Under redelivery those
genuinely differ, and "what happened, in order" means the former.

This is your event log, not ours. Platform-internal records — API key issuance, revocation,
and so on — are not events in this vocabulary and are not returned here, by construction
rather than by a filter someone maintains.

Use this to backfill a webhook outage, or to reconcile after the fact. An event you handled
from a webhook and an event you read here are the same event, with the same id, so
deduplicating across both costs you nothing.

---

## 12. Analytics

```http
GET /v1/analytics/payments?from=2026-07-01T00:00:00Z&to=2026-07-25T00:00:00Z
```

```json
{
  "object": "analytics_summary",
  "from": "2026-07-01T00:00:00Z",
  "to": "2026-07-25T00:00:00Z",
  "createdCount": 1420,
  "authorizedCount": 1301,
  "capturedCount": 1288,
  "refundedCount": 44,
  "voidedCount": 12,
  "failedCount": 107,
  "totalCapturedAmountMinor": 5412200,
  "totalRefundedAmountMinor": 184000,
  "successRate": 0.9240,
  "buckets": [
    {
      "object": "analytics_bucket",
      "bucketStart": "2026-07-01T00:00:00Z",
      "currency": "USD",
      "createdCount": 12,
      "authorizedCount": 11,
      "capturedCount": 11,
      "refundedCount": 0,
      "voidedCount": 0,
      "failedCount": 1,
      "totalCapturedAmountMinor": 46200,
      "totalRefundedAmountMinor": 0
    }
  ]
}
```

Totals and the series come back together, so a dashboard renders from one round trip.

- **Buckets are hourly**, `bucketStart` inclusive, one bucket per currency per hour. Roll
  them up to days yourself if that is what you are charting — the reverse is impossible.
- **`from` / `to` default to the last 7 days** and are truncated outward to bucket
  boundaries, so asking for `09:30`–`10:30` returns the two buckets that actually contain
  your data instead of an empty series.
- **The window may not exceed 90 days**, and a wider request is **rejected** rather than
  quietly shortened. A truncated series charted as though it were the whole story is worse
  than an error.
- **`successRate` is `authorizedCount / (authorizedCount + failedCount)`** — how often an
  authorization attempt succeeded. Payments still in `CREATED` are excluded; they have not
  been attempted yet, and counting them as failures would make your rate fall simply
  because traffic arrived.
- **`successRate` is `null`, not `0`, when nothing was attempted** — and it is always
  present, never omitted. A rate over zero attempts is unknown, not zero: charting it as
  zero shows a catastrophic outage every quiet hour. The explicit `null` is what lets you
  tell "we measured, and there is no answer" from "this version has no such field".

The series starts when hourly recording began. Your all-time totals are unaffected; only
the per-hour breakdown has a start date, and reporting that honestly beats reconstructing a
history that was never recorded.

---

## 13. Forward compatibility

The URL stays `/v1` permanently — `v1` names the API *family*. Revisions are dated and
selected with the `PaymentFlow-Version` header.

These are **not** breaking changes and can ship at any time:

- new fields on any object
- new endpoints
- new event types
- new values in an existing enum

So: ignore fields you do not recognise, and do not crash on an enum value you have never
seen. Both are tested in our own SDKs, and both are the difference between an integration
that survives our next release and one that does not.
