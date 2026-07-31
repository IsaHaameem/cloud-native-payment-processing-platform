# Generated from docs/openapi.yaml by :sdks:shared. Do not edit.
#
# `./gradlew :sdks:shared:generateSdkSources` regenerates this module;
# `./gradlew :sdks:shared:verifySdkSources` fails the build when it is stale,
# which is why hand-editing it is not merely discouraged but pointless.

from __future__ import annotations

from typing import Any, Dict, Final, List, Optional, Tuple, TypedDict

ApiErrorType = str
"""The error's classification, and **the field to branch on**. This is a small closed set an SDK maps to exception classes: `authentication_error`, `permission_error`, `invalid_request_error`, `idempotency_error`, `rate_limit_error`, `api_error`.

New values may be added without a new API revision, so this alias stays ``str``: treat an unrecognised value as one you do not handle rather than as an error.
"""
API_ERROR_TYPE_VALUES: Final[Tuple[str, ...]] = (
    "authentication_error",
    "permission_error",
    "invalid_request_error",
    "idempotency_error",
    "rate_limit_error",
    "api_error",
)

BalanceTransactionResponseDirection = str
"""Whether the entry added to (`CREDIT`) or removed from (`DEBIT`) the account.

New values may be added without a new API revision, so this alias stays ``str``: treat an unrecognised value as one you do not handle rather than as an error.
"""
BALANCE_TRANSACTION_RESPONSE_DIRECTION_VALUES: Final[Tuple[str, ...]] = (
    "DEBIT",
    "CREDIT",
)

BalanceTransactionResponseMode = str
"""Whether this entry is `test` or `live` data.

New values may be added without a new API revision, so this alias stays ``str``: treat an unrecognised value as one you do not handle rather than as an error.
"""
BALANCE_TRANSACTION_RESPONSE_MODE_VALUES: Final[Tuple[str, ...]] = (
    "test",
    "live",
)

CreateSimulationOverrideRequestScenario = str
"""Which behaviour to force. The scenario decides which of the fields below are required: `FORCE_DECLINE` needs `declineCode`, `FORCE_ERROR` needs `errorCode`, and the latency scenarios need `latencyMs`.

New values may be added without a new API revision, so this alias stays ``str``: treat an unrecognised value as one you do not handle rather than as an error.
"""
CREATE_SIMULATION_OVERRIDE_REQUEST_SCENARIO_VALUES: Final[Tuple[str, ...]] = (
    "FORCE_DECLINE",
    "FORCE_ERROR",
    "INJECT_LATENCY",
    "FORCE_TIMEOUT",
    "FORCE_RATE_LIMIT",
    "DELAY_SETTLEMENT",
    "DUPLICATE_WEBHOOKS",
    "WEBHOOK_FAILURE",
)

EventResponseMode = str
"""Whether this event describes `test` or `live` activity.

New values may be added without a new API revision, so this alias stays ``str``: treat an unrecognised value as one you do not handle rather than as an error.
"""
EVENT_RESPONSE_MODE_VALUES: Final[Tuple[str, ...]] = (
    "test",
    "live",
)

PaymentResponseMode = str
"""Whether this payment is `test` or `live` data. Determined by the API key that created it and not changeable by any header, parameter or field.

New values may be added without a new API revision, so this alias stays ``str``: treat an unrecognised value as one you do not handle rather than as an error.
"""
PAYMENT_RESPONSE_MODE_VALUES: Final[Tuple[str, ...]] = (
    "test",
    "live",
)

RefundResponseMode = str
"""Whether this refund is `test` or `live` data. Inherited from the payment.

New values may be added without a new API revision, so this alias stays ``str``: treat an unrecognised value as one you do not handle rather than as an error.
"""
REFUND_RESPONSE_MODE_VALUES: Final[Tuple[str, ...]] = (
    "test",
    "live",
)

RequestLogResponseMode = str
"""Whether the request was made with a `test` or `live` key.

New values may be added without a new API revision, so this alias stays ``str``: treat an unrecognised value as one you do not handle rather than as an error.
"""
REQUEST_LOG_RESPONSE_MODE_VALUES: Final[Tuple[str, ...]] = (
    "test",
    "live",
)

WebhookDeliveryAttemptResponseOutcome = str
"""What happened: the receiver accepted it, rejected it, or was never reached.

New values may be added without a new API revision, so this alias stays ``str``: treat an unrecognised value as one you do not handle rather than as an error.
"""
WEBHOOK_DELIVERY_ATTEMPT_RESPONSE_OUTCOME_VALUES: Final[Tuple[str, ...]] = (
    "SUCCEEDED",
    "FAILED_STATUS",
    "FAILED_TRANSPORT",
    "BLOCKED",
)

WebhookDeliveryResponseStatus = str
"""Where the delivery stands: still pending, delivered, retrying, or dead-lettered after exhausting the retry schedule.

New values may be added without a new API revision, so this alias stays ``str``: treat an unrecognised value as one you do not handle rather than as an error.
"""
WEBHOOK_DELIVERY_RESPONSE_STATUS_VALUES: Final[Tuple[str, ...]] = (
    "PENDING",
    "DELIVERED",
    "DEAD_LETTERED",
)

WebhookEndpointResponseDisabledReason = str
"""Why the endpoint was disabled. **This is how you tell "the platform turned this off because it kept failing" from "I turned this off"** — which decides whether re-enabling it requires fixing something first. Absent on an enabled endpoint.

New values may be added without a new API revision, so this alias stays ``str``: treat an unrecognised value as one you do not handle rather than as an error.
"""
WEBHOOK_ENDPOINT_RESPONSE_DISABLED_REASON_VALUES: Final[Tuple[str, ...]] = (
    "CONSECUTIVE_FAILURES",
)

class AnalyticsBucketResponse(TypedDict, total=False):
    """AnalyticsBucketResponse"""
    authorizedCount: int
    """Payments authorized in this hour."""
    bucketStart: str
    """The inclusive start of this bucket. Each bucket covers the hour beginning here."""
    capturedCount: int
    """Payments captured in this hour."""
    createdCount: int
    """Payments created in this hour."""
    currency: str
    """The ISO 4217 currency these counts and amounts are for. Buckets are split by currency, so amounts are never mixed."""
    failedCount: int
    """Authorization attempts that failed in this hour."""
    object: str
    """Always `analytics_bucket`."""
    refundedCount: int
    """Payments refunded in this hour."""
    totalCapturedAmountMinor: int
    """Total captured in this hour, in the bucket's currency's minor unit."""
    totalRefundedAmountMinor: int
    """Total refunded in this hour, in the bucket's currency's minor unit."""
    voidedCount: int
    """Authorizations released without capture in this hour."""

AnalyticsSummaryResponse = TypedDict(
    "AnalyticsSummaryResponse",
    {
        "authorizedCount": int,
        "buckets": List["AnalyticsBucketResponse"],
        "capturedCount": int,
        "createdCount": int,
        "failedCount": int,
        "from": str,
        "object": str,
        "refundedCount": int,
        "successRate": Optional[float],
        "to": str,
        "totalCapturedAmountMinor": int,
        "totalRefundedAmountMinor": int,
        "voidedCount": int,
    },
    total=False,
)
"""AnalyticsSummaryResponse

``authorizedCount``: How many payments were authorized.

``buckets``: The hourly series behind these totals, split by currency. The series begins where this platform started recording it.

``capturedCount``: How many payments were captured.

``createdCount``: How many payments were created in the window.

``failedCount``: How many authorization attempts failed.

``from``: The start of the window these totals cover, inclusive.

``object``: Always `analytics_summary`.

``refundedCount``: How many payments were refunded, in whole or in part.

``successRate``: The share of authorization attempts that succeeded, between 0 and 1, computed as `authorizedCount / (authorizedCount + failedCount)`. Payments still in `created` are excluded — they have not been attempted, and counting them as failures would make the rate fall simply because traffic arrived.

**Explicitly `null`**, never omitted, when nothing was attempted in the window: a rate over zero attempts is unknown rather than zero.

``to``: The end of the window these totals cover, inclusive.

``totalCapturedAmountMinor``: Total captured across the window, in minor units. Not currency-split — use `buckets` for that.

``totalRefundedAmountMinor``: Total refunded across the window, in minor units.

``voidedCount``: How many authorizations were released without capture.
"""

class ApiError(TypedDict, total=False):
    """ApiError"""
    code: str
    """A stable, machine-readable identifier for this specific failure, such as `PAYMENT_NOT_CAPTURABLE`. The set of codes grows by policy, which is why `type` and not this is what a client should switch on. Every code is catalogued in docs/ERRORS.md."""
    correlationId: str
    """Identifies the whole distributed trace this call belongs to, which may span several services. Broader than `requestId`."""
    docUrl: str
    """Where to read about this specific code."""
    errors: List["ApiFieldError"]
    """Field-level validation failures, when the request failed validation in more than one place."""
    message: str
    """A human-readable explanation. Safe to log; not intended to be shown to your customers, and never programmatically parsed."""
    param: str
    """The single offending parameter, when there is exactly one. Multi-field validation failures use `errors` instead."""
    path: str
    """The request path that produced the error."""
    requestId: str
    """Identifies this one HTTP call. **Quote this in a support request** — it is on every log line and every row of your request log."""
    status: int
    """The HTTP status code, repeated in the body so a logged response is self-contained."""
    timestamp: str
    """When the error occurred, as RFC 3339."""
    type: ApiErrorType
    """The error's classification, and **the field to branch on**. This is a small closed set an SDK maps to exception classes: `authentication_error`, `permission_error`, `invalid_request_error`, `idempotency_error`, `rate_limit_error`, `api_error`."""

class ApiFieldError(TypedDict, total=False):
    """ApiFieldError"""
    field: str
    """The request field that failed validation."""
    message: str
    """Why it failed. The rejected value is deliberately never echoed back."""

class BalanceResponse(TypedDict, total=False):
    """BalanceResponse"""
    balances: List["CurrencyBalance"]
    """One entry per currency you hold a balance in. A currency you have never transacted in is absent rather than reported as zero."""
    object: str
    """Always `balance`. The discriminator that identifies this object out of context."""

class BalanceTransactionResponse(TypedDict, total=False):
    """BalanceTransactionResponse"""
    accountType: str
    """Which of your accounts moved. `MERCHANT_PENDING` holds authorized funds; `MERCHANT_SETTLED` holds captured funds owed to you. The other leg of every entry touches the platform's clearing account and is deliberately not exposed."""
    amountMinor: int
    """The amount moved, in the currency's minor unit. Always positive — `direction` carries the sign."""
    createdAt: str
    """When the entry was posted, as RFC 3339."""
    currency: str
    """The three-letter ISO 4217 currency code."""
    direction: BalanceTransactionResponseDirection
    """Whether the entry added to (`CREDIT`) or removed from (`DEBIT`) the account."""
    eventType: str
    """Which payment lifecycle event produced the entry — an authorization, a capture, a refund, a void. This is the *why* of the movement."""
    id: str
    """Unique identifier for this ledger entry."""
    mode: BalanceTransactionResponseMode
    """Whether this entry is `test` or `live` data."""
    object: str
    """Always `balance_transaction`. The discriminator that identifies this object out of context."""
    paymentId: str
    """The payment whose lifecycle produced this entry."""

class CreatePaymentRequest(TypedDict, total=False):
    """CreatePaymentRequest"""
    amountMinor: int
    """The amount to charge, as an integer in the currency's **minor unit**: `1000` in `USD` is $10.00. There are no floating-point amounts anywhere in this API. Must be positive."""
    currency: str
    """The three-letter ISO 4217 currency code, such as `USD`."""
    description: str
    """An arbitrary description of what is being paid for, up to 500 characters. For your own records — PaymentFlow never shows it to your customer."""
    metadata: Dict[str, str]
    """Up to 20 arbitrary key-value pairs you can attach to this object. PaymentFlow never interprets them; they are returned on every read and are filterable with `metadata[key]=value`."""
    paymentMethodToken: str
    """A payment-method token to authorize against. Optional: a payment created without one takes the mode's default authorization behaviour. In test mode, pass one of the tokens from `GET /v1/test/cards` to choose the outcome."""

class CreateSimulationOverrideRequest(TypedDict, total=False):
    """CreateSimulationOverrideRequest"""
    declineCode: str
    """The decline code to return. Required for `FORCE_DECLINE`, ignored otherwise."""
    durationSeconds: int
    """How long the override lasts, in seconds. The alternative to `remainingCount`; supply one of the two."""
    errorCode: str
    """The error code to fail with. Required for `FORCE_ERROR`, ignored otherwise."""
    latencyMs: int
    """How long to stall, in milliseconds. Required for the latency scenarios, ignored otherwise."""
    remainingCount: int
    """How many authorizations the override applies to before it expires. **Supply this or `durationSeconds`** — an override with neither would never stop, and a sandbox you cannot get back out of is worse than one you cannot get into."""
    scenario: CreateSimulationOverrideRequestScenario
    """Which behaviour to force. The scenario decides which of the fields below are required: `FORCE_DECLINE` needs `declineCode`, `FORCE_ERROR` needs `errorCode`, and the latency scenarios need `latencyMs`."""

class CreateWebhookEndpointRequest(TypedDict, total=False):
    """CreateWebhookEndpointRequest"""
    description: str
    """Your own label for this endpoint. Useful once you have more than one."""
    enabledEvents: List[str]
    """The event types to send here. Must name at least one — an endpoint subscribed to nothing looks identical to a broken platform from your side, so it is refused rather than accepted silently. Use `["*"]` to receive everything, including types added later."""
    metadata: Dict[str, str]
    """Up to 20 arbitrary key-value pairs you can attach to this object. PaymentFlow never interprets them; they are returned on every read and are filterable with `metadata[key]=value`."""
    url: str
    """Where to send deliveries. Must be reachable from the public internet over HTTPS: private, link-local and cloud-metadata addresses are refused, and the address is re-checked at delivery time so a hostname cannot be repointed at one afterwards.

    Not updatable later — the URL is half of an endpoint's identity.
    """

class CurrencyBalance(TypedDict, total=False):
    """CurrencyBalance"""
    availableMinor: int
    """Money captured and owed to you, in the currency's minor unit, net of refunds."""
    currency: str
    """The three-letter ISO 4217 currency code."""
    pendingMinor: int
    """Money authorized but not yet captured, in the currency's minor unit. Not yours yet — an authorization can still be voided or expire."""

class CursorPageBalanceTransactionResponse(TypedDict, total=False):
    """CursorPageBalanceTransactionResponse"""
    data: List["BalanceTransactionResponse"]
    """The objects on this page, most recent first."""
    hasMore: bool
    """Whether more objects exist after this page. A cursor page reports no total count — that would cost a second full count query on every request — and this is the only question a paginating client needs."""
    nextCursor: str
    """Pass this as `starting_after` to fetch the next page. Absent when `hasMore` is false. Opaque and signed: treat it as a token, never parse or construct one."""
    object: str
    """Always `list`. A constant discriminator, so a client deserializing a response can branch on a field rather than on the endpoint it called."""

class CursorPageEventResponse(TypedDict, total=False):
    """CursorPageEventResponse"""
    data: List["EventResponse"]
    """The objects on this page, most recent first."""
    hasMore: bool
    """Whether more objects exist after this page. A cursor page reports no total count — that would cost a second full count query on every request — and this is the only question a paginating client needs."""
    nextCursor: str
    """Pass this as `starting_after` to fetch the next page. Absent when `hasMore` is false. Opaque and signed: treat it as a token, never parse or construct one."""
    object: str
    """Always `list`. A constant discriminator, so a client deserializing a response can branch on a field rather than on the endpoint it called."""

class CursorPagePaymentResponse(TypedDict, total=False):
    """CursorPagePaymentResponse"""
    data: List["PaymentResponse"]
    """The objects on this page, most recent first."""
    hasMore: bool
    """Whether more objects exist after this page. A cursor page reports no total count — that would cost a second full count query on every request — and this is the only question a paginating client needs."""
    nextCursor: str
    """Pass this as `starting_after` to fetch the next page. Absent when `hasMore` is false. Opaque and signed: treat it as a token, never parse or construct one."""
    object: str
    """Always `list`. A constant discriminator, so a client deserializing a response can branch on a field rather than on the endpoint it called."""

class CursorPageRefundResponse(TypedDict, total=False):
    """CursorPageRefundResponse"""
    data: List["RefundResponse"]
    """The objects on this page, most recent first."""
    hasMore: bool
    """Whether more objects exist after this page. A cursor page reports no total count — that would cost a second full count query on every request — and this is the only question a paginating client needs."""
    nextCursor: str
    """Pass this as `starting_after` to fetch the next page. Absent when `hasMore` is false. Opaque and signed: treat it as a token, never parse or construct one."""
    object: str
    """Always `list`. A constant discriminator, so a client deserializing a response can branch on a field rather than on the endpoint it called."""

class CursorPageRequestLogResponse(TypedDict, total=False):
    """CursorPageRequestLogResponse"""
    data: List["RequestLogResponse"]
    """The objects on this page, most recent first."""
    hasMore: bool
    """Whether more objects exist after this page. A cursor page reports no total count — that would cost a second full count query on every request — and this is the only question a paginating client needs."""
    nextCursor: str
    """Pass this as `starting_after` to fetch the next page. Absent when `hasMore` is false. Opaque and signed: treat it as a token, never parse or construct one."""
    object: str
    """Always `list`. A constant discriminator, so a client deserializing a response can branch on a field rather than on the endpoint it called."""

class DecisionLogEntryResponse(TypedDict, total=False):
    """DecisionLogEntryResponse"""
    createdAt: str
    """When the decision was made, as RFC 3339."""
    decisionKey: str
    """The idempotency key this decision was made under. Re-deciding with the same key returns the original verdict rather than deciding again, so a retry cannot change an outcome your integration already saw."""
    declineCode: str
    """The decline code returned, when the outcome was a decline."""
    deferredDelayMs: int
    """How long the deferred outcome was scheduled to wait, in milliseconds."""
    deferredOperation: str
    """The operation whose outcome was deferred, when the decision scheduled one for later."""
    errorCode: str
    """The error code returned, when the outcome was an error."""
    latencyMs: int
    """How long the simulated acquirer took to answer, in milliseconds."""
    operation: str
    """Which operation was being decided — an authorization, a capture, a refund."""
    outcome: str
    """What the simulated acquirer decided."""
    overrideId: str
    """The simulation override that produced this decision, when `source` names one. A bare reference rather than the override's contents — enough to know one was involved without this endpoint re-exposing the simulation control surface."""
    paymentId: str
    """The payment this decision was made about."""
    source: str
    """**Why the outcome was what it was**: the test card's own catalogue entry, an active simulation override, or the mode's default. This is the field that turns a surprising result into an explained one."""

class EventResponse(TypedDict, total=False):
    """EventResponse"""
    created: str
    """When the event occurred — not when it was recorded. Events are ordered by this, so a redelivery cannot reorder your feed."""
    data: Dict[str, Any]
    """The event payload — the object the event happened to, as it was at the time. Its shape depends on `type` and is the same body your webhook endpoint received. Stored verbatim: audit-service records what it was given rather than a typed view it would then have to keep in step."""
    id: str
    """Unique identifier for this event, `evt_` followed by 32 hex characters. **Byte-identical to the `id` in the webhook body you received for the same event**, so a webhook can be reconciled against this log without storing anything extra."""
    mode: EventResponseMode
    """Whether this event describes `test` or `live` activity."""
    object: str
    """Always `event`. The discriminator that identifies this object out of context."""
    type: str
    """What happened, from the frozen event vocabulary — `payment.created`, `payment.authorized`, `payment.captured`, `payment.refunded`, `payment.voided`, `payment.failed`. **New types may be added without a new API revision**, so treat an unrecognised type as one you do not handle."""

class PageResponseDecisionLogEntryResponse(TypedDict, total=False):
    """PageResponseDecisionLogEntryResponse"""
    content: List["DecisionLogEntryResponse"]
    """The objects on this page."""
    first: bool
    """Whether this is the first page."""
    last: bool
    """Whether this is the last page."""
    page: int
    """The zero-based index of this page."""
    size: int
    """How many objects each page holds."""
    totalElements: int
    """How many objects match the query in total."""
    totalPages: int
    """How many pages the result set spans."""

class PageResponseWebhookDeliveryResponse(TypedDict, total=False):
    """PageResponseWebhookDeliveryResponse"""
    content: List["WebhookDeliveryResponse"]
    """The objects on this page."""
    first: bool
    """Whether this is the first page."""
    last: bool
    """Whether this is the last page."""
    page: int
    """The zero-based index of this page."""
    size: int
    """How many objects each page holds."""
    totalElements: int
    """How many objects match the query in total."""
    totalPages: int
    """How many pages the result set spans."""

class PaymentResponse(TypedDict, total=False):
    """PaymentResponse"""
    amountMinor: int
    """The amount authorized, as an integer in the currency's minor unit: `1000` in `USD` is $10.00."""
    capturedAmountMinor: int
    """How much of the authorized amount has been captured so far, in minor units."""
    createdAt: str
    """When the payment was created, as RFC 3339."""
    currency: str
    """The three-letter ISO 4217 currency code."""
    description: str
    """The description supplied when the payment was created."""
    failureReason: str
    """Why the payment failed, when `status` is `failed`. Absent otherwise — this is the acquirer's reason, not a validation message."""
    id: str
    """Unique identifier for this payment."""
    merchantId: str
    """The merchant this payment belongs to — always your own account."""
    metadata: Dict[str, str]
    """Up to 20 arbitrary key-value pairs you can attach to this object. PaymentFlow never interprets them; they are returned on every read and are filterable with `metadata[key]=value`."""
    mode: PaymentResponseMode
    """Whether this payment is `test` or `live` data. Determined by the API key that created it and not changeable by any header, parameter or field."""
    object: str
    """Always `payment`. The discriminator that identifies this object out of context."""
    paymentMethodToken: str
    """The payment-method token this payment authorizes against, if one was supplied."""
    refundedAmountMinor: int
    """How much has been refunded so far, in minor units. Never exceeds `capturedAmountMinor`."""
    refunds: List["RefundResponse"]
    """The refunds issued against this payment. **Present only when you ask for it** with `?expand=refunds`, and omitted entirely otherwise rather than returned empty — an empty list would be indistinguishable from "this payment has no refunds"."""
    status: str
    """Where this payment is in its lifecycle. Lowercase `snake_case` as of API revision `2026-08-01`; callers pinned to `2026-07-27` receive the older upper-case spelling. **New values may be added without a new revision**, so treat an unrecognised status as one you do not handle rather than as an error."""
    updatedAt: str
    """When the payment last changed, as RFC 3339."""

class RefundRequest(TypedDict, total=False):
    """RefundRequest"""
    amountMinor: int
    """How much to refund, in the currency's minor unit. Omit it — or send no body at all — to refund everything that remains captured."""
    metadata: Dict[str, str]
    """Up to 20 arbitrary key-value pairs you can attach to this object. PaymentFlow never interprets them; they are returned on every read and are filterable with `metadata[key]=value`."""
    reason: str
    """Why the refund was issued, up to 500 characters. For your own records; PaymentFlow never interprets it."""

class RefundResponse(TypedDict, total=False):
    """RefundResponse"""
    amountMinor: int
    """The amount refunded, in the currency's minor unit."""
    createdAt: str
    """When the refund was created, as RFC 3339."""
    currency: str
    """The three-letter ISO 4217 currency code, matching the payment's."""
    failureReason: str
    """Why the refund failed, when `status` is `failed`. Absent otherwise."""
    id: str
    """Unique identifier for this refund."""
    merchantId: str
    """The merchant this refund belongs to — always your own account."""
    metadata: Dict[str, str]
    """Up to 20 arbitrary key-value pairs you can attach to this object. PaymentFlow never interprets them; they are returned on every read and are filterable with `metadata[key]=value`."""
    mode: RefundResponseMode
    """Whether this refund is `test` or `live` data. Inherited from the payment."""
    object: str
    """Always `refund`. The discriminator that identifies this object out of context."""
    paymentId: str
    """The payment this refund was issued against."""
    reason: str
    """The reason supplied when the refund was issued, if any."""
    status: str
    """Where this refund is in its lifecycle. Lowercase `snake_case` as of API revision `2026-08-01`. New values may be added without a new revision."""
    updatedAt: str
    """When the refund last changed, as RFC 3339."""

class RequestLogResponse(TypedDict, total=False):
    """RequestLogResponse"""
    clientIp: str
    """The client address the request arrived from."""
    correlationId: str
    """Identifies the whole distributed trace this call belongs to."""
    durationMs: int
    """How long the platform took to answer, in milliseconds. Server time only — it excludes the network."""
    errorCode: str
    """The catalogued error code, when the request failed. Absent for a successful request."""
    id: str
    """Unique identifier for this log entry."""
    keyId: str
    """The API key the request was made with. Absent for a key that has since been deleted."""
    method: str
    """The HTTP method."""
    mode: RequestLogResponseMode
    """Whether the request was made with a `test` or `live` key."""
    object: str
    """Always `request_log`."""
    occurredAt: str
    """When the request arrived, as RFC 3339."""
    path: str
    """The path requested, as sent."""
    queryString: str
    """The query string, if any, with sensitive values already redacted."""
    requestBody: str
    """The request body, **already redacted and truncated** at the edge before it was ever stored — card numbers, keys and secrets never reach this log."""
    requestHeaders: Dict[str, str]
    """The request headers, with credential-bearing values redacted."""
    requestId: str
    """Identifies this one HTTP call — the same value the response's error body carried, if it failed."""
    responseBody: str
    """The response body, redacted and truncated on the same terms as the request body."""
    statusCode: int
    """The HTTP status returned."""
    userAgent: str
    """The `User-Agent` header, useful for telling one of your services from another."""

class SimulationOverrideResponse(TypedDict, total=False):
    """SimulationOverrideResponse"""
    declineCode: Optional[str]
    """The decline code being returned, for a decline scenario. **Null** otherwise."""
    enactedFrom: Optional[str]
    """Which part of the platform acts on this scenario. **`null` means the decision engine enforces it now**; a value names the release that will. The webhook scenarios are stored and validated but not yet acted on, and saying so here is more honest than accepting an override that silently does nothing."""
    errorCode: Optional[str]
    """The error code being returned, for an error scenario. **Null** otherwise."""
    expiresAt: Optional[str]
    """When the override stops applying. **Null** for a count-bounded override."""
    id: str
    """Unique identifier for this override."""
    latencyMs: Optional[int]
    """The latency being injected, in milliseconds. **Null** for a scenario that injects none."""
    remainingCount: Optional[int]
    """How many authorizations the override still applies to. Counts down as it is used; **null** for a time-bounded override."""
    scenario: str
    """The behaviour being forced."""

class TestCardResponse(TypedDict, total=False):
    """TestCardResponse"""
    brand: str
    """The card brand this token simulates."""
    captureBehaviour: str
    """What a later capture against this token will do — succeed immediately, fail, or settle after a delay. Authorization and capture can behave differently on purpose: a card that authorizes cleanly and then fails to capture is a real and easily-missed case."""
    declineCode: Optional[str]
    """The decline code the authorization will carry, when `outcome` is a decline. **Null** otherwise."""
    deferredDelayMs: Optional[int]
    """How long a deferred capture or refund waits before its outcome arrives, in milliseconds. **Null** on the tokens whose behaviour is immediate; where it is set, the result reaches you as a webhook, exactly as an asynchronous settlement would."""
    description: str
    """What this token is for, in one line."""
    errorCode: Optional[str]
    """The error code the authorization will fail with, when `outcome` is an error. **Null** otherwise."""
    latencyMs: int
    """How long the simulated acquirer will take to answer, in milliseconds. Non-zero on the tokens that exist to let you exercise timeouts deliberately rather than by waiting for a bad day."""
    outcome: str
    """What authorizing against this token does: approve, decline, or fail with an error. A decline is the acquirer saying no and is a normal outcome your integration must handle; an error is the acquirer being unreachable or broken."""
    refundBehaviour: str
    """What a later refund against this token will do."""
    token: str
    """The token to pass as `paymentMethodToken` when creating a payment. This is what selects the behaviour described by the rest of this object."""

class UpdateWebhookEndpointRequest(TypedDict, total=False):
    """UpdateWebhookEndpointRequest"""
    description: str
    """A new label for this endpoint. Omit to leave it unchanged."""
    enabled: bool
    """Turn deliveries on or off. Re-enabling an endpoint the platform disabled resets its failure count — fix the receiver first, or it will simply be disabled again."""
    enabledEvents: List[str]
    """Replace the subscription list. Sent wholesale rather than merged, so this is the complete new list. Omit to leave it unchanged."""
    metadata: Dict[str, str]
    """Replace the metadata. A supplied map replaces the stored one wholesale rather than merging, so `{}` clears it — a merge would leave no way to remove a key. Omit to leave it unchanged."""

class UsageBucketResponse(TypedDict, total=False):
    """UsageBucketResponse"""
    clientErrors: int
    """How many returned a 4xx."""
    day: str
    """The day this bucket covers, in UTC."""
    keyId: Optional[str]
    """The API key the traffic was made with. Usage is grouped by key, so two keys hitting the same route on the same day produce two buckets. **Null** for traffic made with a key that has since been deleted — the usage is still a fact about your day."""
    maxDurationMs: int
    """The slowest single request in the bucket, in milliseconds."""
    meanDurationMs: Optional[int]
    """Mean server-side duration in milliseconds. Null when the bucket had no traffic."""
    p50DurationMs: Optional[int]
    """Median server-side duration in milliseconds. Null when the bucket had no traffic."""
    p95DurationMs: Optional[int]
    """95th-percentile server-side duration in milliseconds. **Explicitly null**, never omitted, when the bucket had no traffic: a percentile over zero requests is unknown rather than zero."""
    p99DurationMs: Optional[int]
    """99th-percentile server-side duration in milliseconds. Null when the bucket had no traffic."""
    requests: int
    """How many requests fell in this bucket."""
    route: str
    """The route pattern the requests hit, not the concrete path — so every payment retrieval aggregates together."""
    serverErrors: int
    """How many returned a 5xx."""

UsageSummaryResponse = TypedDict(
    "UsageSummaryResponse",
    {
        "buckets": List["UsageBucketResponse"],
        "from": str,
        "to": str,
        "totalClientErrors": int,
        "totalRequests": int,
        "totalServerErrors": int,
    },
    total=False,
)
"""UsageSummaryResponse

``buckets``: The breakdown behind the totals, one bucket per day, key and route.

``from``: The first day these totals cover, inclusive.

``to``: The last day these totals cover, inclusive.

``totalClientErrors``: How many of those returned a 4xx — your requests that were rejected.

``totalRequests``: How many API requests you made across the range.

``totalServerErrors``: How many returned a 5xx — this platform's failures, not yours.
"""

class WebhookDeliveryAttemptResponse(TypedDict, total=False):
    """WebhookDeliveryAttemptResponse"""
    attemptNumber: int
    """Which attempt this was, starting at 1."""
    attemptedAt: str
    """When the attempt was made, as RFC 3339."""
    durationMs: int
    """How long the attempt took, in milliseconds."""
    error: str
    """Why the attempt failed before it got a response — a DNS failure, a refused connection, a timeout, or an address the egress guard blocked. Absent when the receiver answered at all, even with an error status."""
    id: str
    """Unique identifier for this attempt."""
    outcome: WebhookDeliveryAttemptResponseOutcome
    """What happened: the receiver accepted it, rejected it, or was never reached."""
    requestBody: str
    """The body sent — byte for byte what the signature was computed over."""
    requestHeaders: str
    """The headers sent, **including the `PaymentFlow-Signature`** this platform computed. That is deliberate and safe — a signature is not a secret, and comparing the value sent against the one you computed is how a verification failure gets diagnosed. The signing secret itself never appears here."""
    requestUrl: str
    """The URL this attempt was sent to."""
    responseBody: str
    """The body the receiver returned, truncated. Often the only explanation of a rejection."""
    responseHeaders: str
    """The headers the receiver returned."""
    responseStatus: int
    """The HTTP status the receiver returned. Absent when it was never reached."""

class WebhookDeliveryResponse(TypedDict, total=False):
    """WebhookDeliveryResponse"""
    attemptCount: int
    """How many attempts have been made so far."""
    attempts: List["WebhookDeliveryAttemptResponse"]
    """Every attempt, with the request sent and the response received. This is the point of the delivery log: the answer a merchant needs is almost never "failed", it is "failed with 502 and this body, four times"."""
    createdAt: str
    """When the delivery was created, as RFC 3339."""
    endpointId: str
    """The endpoint this delivery is addressed to."""
    eventId: str
    """The event being delivered. The same `evt_` id you can read back at `/v1/events`, so a delivery can always be traced to what caused it."""
    eventType: str
    """The type of the event being delivered."""
    id: str
    """Unique identifier for this delivery."""
    lastAttemptedAt: str
    """When it was last attempted. Absent if it has not been attempted yet."""
    nextAttemptAt: str
    """When the next retry is scheduled. Absent once the delivery has succeeded or been dead-lettered."""
    object: str
    """Always `webhook_delivery`."""
    replayedFromDeliveryId: str
    """The delivery this one re-sends, when it was created by a replay. Absent on an original delivery."""
    status: WebhookDeliveryResponseStatus
    """Where the delivery stands: still pending, delivered, retrying, or dead-lettered after exhausting the retry schedule."""
    url: str
    """The URL it is being sent to, as it was when the delivery was created."""

class WebhookEndpointCreatedResponse(TypedDict, total=False):
    """WebhookEndpointCreatedResponse"""
    endpoint: "WebhookEndpointResponse"
    """The endpoint, in the shape every other read returns it in."""
    signingSecret: str
    """The signing secret, in full. **Shown exactly once — store it now.** Every delivery to this endpoint is signed with it, and verifying that signature is the only thing standing between your receiver and anyone who learns its URL. It cannot be retrieved afterwards: only a hash is kept, so the platform genuinely cannot show it again rather than merely declining to. Lost one is replaced by rotating, not by recovering."""

class WebhookEndpointResponse(TypedDict, total=False):
    """WebhookEndpointResponse"""
    apiVersion: str
    """The API revision delivery bodies are rendered in for this endpoint."""
    consecutiveFailureCount: int
    """How many deliveries have failed in a row. Resets to zero on the first success. When it reaches the platform's threshold the endpoint is disabled automatically — a permanently broken receiver is not retried forever."""
    createdAt: str
    """When the endpoint was registered, as RFC 3339."""
    description: str
    """Your own label for this endpoint."""
    disabledAt: str
    """When the endpoint was disabled, if it is."""
    disabledReason: WebhookEndpointResponseDisabledReason
    """Why the endpoint was disabled. **This is how you tell "the platform turned this off because it kept failing" from "I turned this off"** — which decides whether re-enabling it requires fixing something first. Absent on an enabled endpoint."""
    enabled: bool
    """Whether deliveries are currently being sent. May be false because you disabled it or because the platform did — see `disabledReason`."""
    enabledEvents: List[str]
    """The event types this endpoint receives. `*` means every type, including ones added later."""
    id: str
    """Unique identifier for this endpoint."""
    metadata: Dict[str, str]
    """Up to 20 arbitrary key-value pairs you can attach to this object. PaymentFlow never interprets them; they are returned on every read and are filterable with `metadata[key]=value`."""
    migratedFromLegacy: bool
    """Whether this endpoint was carried over from the platform's pre-webhook-product configuration."""
    object: str
    """Always `webhook_endpoint`."""
    signingSecretPrefix: str
    """The first few characters of the signing secret, so you can tell which secret an endpoint holds. **The full secret is shown exactly once**, when the endpoint is created or its secret is rotated, and is not retrievable afterwards."""
    updatedAt: str
    """When the endpoint last changed, as RFC 3339."""
    url: str
    """Where deliveries are sent. Not updatable: the URL is half of an endpoint's identity, and repointing one would leave its delivery history attached to a destination that never received any of it. Register a new endpoint instead."""
