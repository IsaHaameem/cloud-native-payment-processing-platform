# PaymentFlow API Versioning

How the PaymentFlow API changes, and what that means for your integration. Like the
[read-API guide](READ_APIS.md) and the [error catalogue](ERRORS.md), this is written for an
integrator, and the versions and dates in it are asserted against the code by
`ApiVersionTest` rather than transcribed by hand.

---

## 1. Versions are dates

```http
GET /v1/payments HTTP/1.1
Host: api.paymentflow.dev
Authorization: Bearer sk_test_...
PaymentFlow-Version: 2026-08-01
```

The URL prefix stays `/v1/` permanently. `v1` names the API *family*; the
`PaymentFlow-Version` header names the *revision*. A `/v2/` path would only ever appear for
a total redesign, and nothing on the roadmap needs one.

**Currently served:**

| Version | Status | Sunset |
|---|---|---|
| `2026-08-01` | **Current** — what you get if you do nothing | — |
| `2026-07-27` | Superseded, still served | 1 August 2027 |

---

## 2. Which version answers your request

In order of precedence:

1. **The `PaymentFlow-Version` header**, if you send one. A per-request override.
2. **Your account's pinned version**, set automatically on your first API call.
3. **The current version**, if you have neither.

**You are pinned on your first call, not at signup.** Whatever revision was current the first
time you actually called the API is the one your account keeps — so the contract you
integrated against is the contract you keep receiving, indefinitely, without doing anything.

Every response tells you which version answered it:

```http
PaymentFlow-Version: 2026-08-01
```

Sending a version that does not exist is an error rather than a silent fallback — you get a
`400` with code `UNSUPPORTED_API_VERSION` listing the versions that do. Answering in a
different revision than you asked for would hand you a shape you did not request with no way
to notice.

---

## 3. What changes without a new version

**Additive changes ship continuously and are not breaking.** Your client must tolerate:

- new fields on existing responses,
- new endpoints,
- new event types,
- **new values in existing enums**, including new `status` values and new error `code`s.

That last one is why errors carry both a `type` and a `code` — [`type`](ERRORS.md#2-branch-on-type-not-on-code)
is a closed set you can safely branch on, `code` is not.

A client that rejects unknown fields, or that has an exhaustive `switch` over an enum with no
default branch, will break on a change that this policy considers safe. The SDKs handle this
for you and it is a tested requirement of theirs.

---

## 4. What requires a new version

Anything a correct client could notice as a removal or a change in meaning:

- removing or renaming a field,
- changing a field's type,
- changing the meaning or spelling of an existing enum value,
- adding a required request parameter,
- changing an endpoint's status codes or error semantics.

When one of these happens, a new dated revision is published and **the previous one keeps
working**, unchanged, translated at the edge. You upgrade when you choose to.

---

## 5. Deprecation and sunset

A superseded revision carries standard headers on every response:

```http
PaymentFlow-Version: 2026-07-27
Deprecation: true
Sunset: Sun, 01 Aug 2027 00:00:00 GMT
Link: <https://docs.paymentflow.dev/versioning>; rel="deprecation"
```

Superseded revisions are supported for **at least twelve months** from the day they are
superseded. The `Sunset` date is a commitment, not an estimate — monitor for these headers
in your logging and you will never be surprised.

At most one superseded revision is served at a time. Before a third revision appears, the
oldest is sunset. This is a deliberate cap: every supported revision is a translation that
has to stay correct forever.

---

## 6. Upgrading

1. **Read the change.** §7 below lists exactly what differs between revisions.
2. **Try it on one call** by sending `PaymentFlow-Version: <new>` on a single request. Your
   account's pin is untouched — this is the whole reason the header outranks it.
3. **Update your code**, then repin your account.

You can also go the other way for as long as the old revision is served: sending
`PaymentFlow-Version: 2026-07-27` from a current-pinned account reproduces the older shape,
which is useful for confirming a bug report from a customer still on it.

---

## 7. Changes between revisions

### `2026-08-01`

**Payment and refund `status` values are lowercase.**

| `2026-07-27` | `2026-08-01` |
|---|---|
| `CREATED` | `created` |
| `AUTHORIZED` | `authorized` |
| `CAPTURED` | `captured` |
| `PARTIALLY_REFUNDED` | `partially_refunded` |
| `REFUNDED` | `refunded` |
| `FAILED` | `failed` |
| `VOIDED` | `voided` |
| `SUCCEEDED` (refunds) | `succeeded` |

This affects the `status` field on payment and refund objects, wherever they appear —
including inside list envelopes. The `status` **filter** on `GET /v1/payments` and
`GET /v1/refunds` accepts either case in both revisions.

Nothing else changed. Webhook event payloads are versioned per endpoint and are not affected
by this revision.
