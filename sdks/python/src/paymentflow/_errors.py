"""The typed exception hierarchy (M22.5).

The same seven classes as the Node SDK, for the same reason §7.1 gives: a client that raises
one exception for everything forces every integrator to write the same ``if status == 401``
ladder, in their own words, against a contract they have to read to discover.

One name differs, deliberately. Python has a builtin ``PermissionError``, and shadowing it
would be a genuine trap — ``from paymentflow import PermissionError`` followed by
``except PermissionError`` in the same module would silently stop catching filesystem errors.
This SDK raises :class:`PermissionDeniedError` instead (D178). §7.1 says the hierarchy is
identical across languages and *only idiom varies*; not colliding with a builtin is idiom.

The class is chosen from ``ApiError.type`` rather than from the HTTP status, because a 409 is
both ``IDEMPOTENCY_CONFLICT`` — which may succeed on a later attempt — and
``PAYMENT_NOT_CAPTURABLE``, which never will. The platform distinguishes them; the status does
not. Status is the fallback, and has to be: a 502 written by a load balancer that never
reached this platform has no ``type``, no body, and frequently no JSON at all.
"""

from __future__ import annotations

from typing import Any, Dict, List, Mapping, Optional, Sequence, Type

__all__ = [
    "ApiConnectionError",
    "ApiError",
    "AuthenticationError",
    "IdempotencyError",
    "InvalidRequestError",
    "PaymentFlowError",
    "PermissionDeniedError",
    "RateLimitError",
    "WebhookPayloadError",
    "WebhookSignatureError",
    "WebhookTimestampError",
    "WebhookVerificationError",
]


class PaymentFlowError(Exception):
    """The base class every exception this SDK raises inherits from.

    Catching this and nothing else is a complete, correct handler — which is the point. An
    integrator who wants to distinguish cases narrows from here; one who does not is still
    safe.
    """

    def __init__(
        self,
        message: str,
        *,
        status_code: Optional[int] = None,
        type: Optional[str] = None,
        code: Optional[str] = None,
        param: Optional[str] = None,
        field_errors: Optional[Sequence[Mapping[str, Any]]] = None,
        request_id: Optional[str] = None,
        correlation_id: Optional[str] = None,
        doc_url: Optional[str] = None,
        attempts: Optional[int] = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        #: The HTTP status, when a response was received at all.
        self.status_code = status_code
        #: The error's classification, as published by the platform.
        self.type = type
        #: The stable, machine-readable code, such as ``PAYMENT_NOT_CAPTURABLE``.
        self.code = code
        #: The single offending parameter, when there is exactly one.
        self.param = param
        #: Field-level validation failures, when more than one field was rejected.
        self.field_errors: Sequence[Mapping[str, Any]] = tuple(field_errors or ())
        #: Identifies this one HTTP call. Quote it in a support request.
        self.request_id = request_id
        #: Identifies the whole distributed trace, which may span several services.
        self.correlation_id = correlation_id
        #: Where to read about this specific code.
        self.doc_url = doc_url
        #: How many HTTP attempts were made before this was raised.
        self.attempts = attempts


class AuthenticationError(PaymentFlowError):
    """The API key is missing, malformed, or not recognised. Retrying will not help."""


class PermissionDeniedError(PaymentFlowError):
    """The key is valid but is not allowed to do this — a missing scope, or the wrong mode.

    Named for Python rather than for §7.1's table: see this module's docstring, and D178.
    """


class InvalidRequestError(PaymentFlowError):
    """The request was understood and rejected.

    A validation failure, an unknown id, or a state the resource cannot move from. ``param``
    and ``field_errors`` say which part.
    """


class IdempotencyError(PaymentFlowError):
    """An ``Idempotency-Key`` problem — most often a concurrent request holding the same key.

    Distinct from :class:`InvalidRequestError` despite sharing a status, because this one
    *may* succeed on a later attempt and the other never will. The platform separates
    ``IDEMPOTENCY_CONFLICT`` from ``CONFLICT`` for exactly this reason.
    """


class RateLimitError(PaymentFlowError):
    """The rate limit or the daily quota was exceeded.

    ``retry_after_seconds`` is the platform's own answer to "when may I try again". The retry
    loop already waits out anything short; this attribute is for a caller who has exhausted
    the budget and wants to schedule the work rather than drop it.
    """

    def __init__(self, message: str, *, retry_after_seconds: Optional[float] = None, **detail: Any) -> None:
        super().__init__(message, **detail)
        #: Seconds to wait before retrying, when the response said.
        self.retry_after_seconds = retry_after_seconds


class ApiConnectionError(PaymentFlowError):
    """The request never produced a response: DNS, a reset connection, or a timeout.

    There is no ``status_code``, because there was no reply — which is also why this is the
    one error where "did it happen?" is genuinely unknown, and why the idempotency key matters
    most here.
    """


class ApiError(PaymentFlowError):
    """The platform failed to handle a request it accepted, or sent a success this SDK could
    not read. Not the caller's fault, and worth reporting with the ``request_id``."""


# ── Webhooks ────────────────────────────────────────────────────────────────────────────


class WebhookVerificationError(PaymentFlowError):
    """A webhook delivery that could not be trusted.

    Separate from the HTTP hierarchy because it describes the opposite direction: raised while
    *receiving* something the platform sent, not while calling it. Nothing here has a
    ``status_code``, because there was no request.
    """


class WebhookSignatureError(WebhookVerificationError):
    """The signature header was malformed, or no signature in it matched.

    **Treat this as hostile.** A body that fails verification did not come from PaymentFlow,
    or did not arrive intact, and either way must not be acted on.
    """


class WebhookTimestampError(WebhookVerificationError):
    """The signature was valid and its timestamp is outside the tolerance window.

    Distinct from :class:`WebhookSignatureError` because it is a different operational problem
    with a different fix. A valid signature arriving late is usually a replayed delivery —
    which is what the timestamp exists to make detectable — but it is also what a clock skewed
    by minutes looks like. One of those is an attack and the other is NTP.
    """

    def __init__(self, message: str, *, timestamp: int, skew_seconds: int) -> None:
        super().__init__(message)
        #: The ``t`` value the header carried, in epoch seconds.
        self.timestamp = timestamp
        #: How far outside the window it fell, in seconds. Always positive.
        self.skew_seconds = skew_seconds


class WebhookPayloadError(WebhookVerificationError):
    """The signature verified, and the body is not an event envelope this SDK can return.

    Reachable only from a platform defect, and raised rather than papered over because
    ``construct_event`` promises an event whose ``id``, ``type`` and ``data`` are present.
    """


# ── Mapping a response to a class ───────────────────────────────────────────────────────

#: The ``type`` values this SDK maps to a class, spelled as the platform publishes them.
_BY_TYPE: Dict[str, Type[PaymentFlowError]] = {
    "authentication_error": AuthenticationError,
    "permission_error": PermissionDeniedError,
    "invalid_request_error": InvalidRequestError,
    "idempotency_error": IdempotencyError,
    "rate_limit_error": RateLimitError,
    "api_error": ApiError,
}


def _by_status(status_code: Optional[int]) -> Type[PaymentFlowError]:
    """What to raise when the body carried no usable ``type``."""
    if status_code == 401:
        return AuthenticationError
    if status_code == 403:
        return PermissionDeniedError
    if status_code == 429:
        return RateLimitError
    if status_code is not None and 400 <= status_code < 500:
        return InvalidRequestError
    return ApiError


def error_from_response(
    body: Any,
    *,
    status_code: int,
    request_id: Optional[str] = None,
    correlation_id: Optional[str] = None,
    retry_after_seconds: Optional[float] = None,
    attempts: int = 1,
) -> PaymentFlowError:
    """Builds the exception for a response the platform refused.

    ``body`` is whatever came back, which may be a well-formed error envelope, JSON of some
    other shape, or nothing at all — every one of those is reachable in production and none of
    them may raise from here. An error constructor that can itself fail replaces a diagnosable
    failure with an undiagnosable one.
    """
    api: Mapping[str, Any] = body if isinstance(body, dict) else {}

    raw_type = api.get("type")
    error_type = raw_type if isinstance(raw_type, str) else None
    # An unrecognised type falls back to the status rather than failing: §9 lets new error
    # types ship without a new API revision, so raising "unknown error type" would turn the
    # platform's safest change into an incident in every integrator's code at once.
    constructor = _BY_TYPE.get(error_type or "") or _by_status(status_code)

    raw_message = api.get("message")
    message = (
        raw_message
        if isinstance(raw_message, str) and raw_message
        else f"The API returned HTTP {status_code} with no error message."
    )

    errors = api.get("errors")
    field_errors: List[Mapping[str, Any]] = [e for e in errors if isinstance(e, dict)] if isinstance(errors, list) else []

    detail: Dict[str, Any] = {
        "status_code": status_code,
        "type": error_type,
        "code": api.get("code") if isinstance(api.get("code"), str) else None,
        "param": api.get("param") if isinstance(api.get("param"), str) else None,
        "field_errors": field_errors,
        # The body's own requestId wins over the header, because it is the value the platform
        # wrote for this failure; the header is the fallback for a response with no body.
        "request_id": api.get("requestId") if isinstance(api.get("requestId"), str) else request_id,
        "correlation_id": api.get("correlationId") if isinstance(api.get("correlationId"), str) else correlation_id,
        "doc_url": api.get("docUrl") if isinstance(api.get("docUrl"), str) else None,
        "attempts": attempts,
    }

    if constructor is RateLimitError:
        return RateLimitError(message, retry_after_seconds=retry_after_seconds, **detail)
    return constructor(message, **detail)
