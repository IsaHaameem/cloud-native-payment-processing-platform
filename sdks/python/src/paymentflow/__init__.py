"""The PaymentFlow SDK for Python.

::

    from paymentflow import PaymentFlow

    client = PaymentFlow(api_key="sk_test_…")
    payment = client.payments.create(amount_minor=1000, currency="USD")
    for each in client.payments.list(status="captured"):
        print(each["id"])

What this file is for
---------------------
This is the package's public API, decided here and nowhere else. ``paymentflow._generated`` is
written by ``:sdks:shared`` from ``docs/openapi.yaml`` and is regenerated in full whenever the
contract moves; re-exporting it wholesale would make every name an integrator can import a
function of a code generator's decisions, so a refactor of the generator — or a schema renamed
inside a Java service — would silently become a breaking change to this package.

So the generated module's *runtime* values (the operation table, the enum value tuples) are
never exported at all, and the generated *types* are re-exported one by one, by name, from the
list below. Adding a model to the contract does not add it to this SDK's API; someone decides.
``tests/test_public_surface.py`` asserts both halves of that rather than trusting it (D172).
"""

from __future__ import annotations

from typing import Final

from . import _webhooks as webhooks
from ._client import PaymentFlow
from ._config import USER_AGENT, PaymentFlowConfigurationError
from ._config import VERSION as _VERSION
from ._errors import (
    ApiConnectionError,
    ApiError,
    AuthenticationError,
    IdempotencyError,
    InvalidRequestError,
    PaymentFlowError,
    PermissionDeniedError,
    RateLimitError,
    WebhookPayloadError,
    WebhookSignatureError,
    WebhookTimestampError,
    WebhookVerificationError,
)
from ._generated.contract import API_VERSION as _API_VERSION
from ._generated.contract import DEFAULT_BASE_URL as _DEFAULT_BASE_URL

# Types only. These describe the objects the API returns, so an integrator needs to be able to
# name them; what they must not be able to do is depend on a name this package never meant to
# publish. The request models are deliberately absent — what a caller passes is a keyword
# argument, which under D170 states requirements the generated model does not.
from ._generated.models import (
    AnalyticsBucketResponse,
    AnalyticsSummaryResponse,
    ApiFieldError,
    BalanceResponse,
    BalanceTransactionResponse,
    CurrencyBalance,
    DecisionLogEntryResponse,
    EventResponse,
    PaymentResponse,
    RefundResponse,
    RequestLogResponse,
    SimulationOverrideResponse,
    TestCardResponse,
    UsageBucketResponse,
    UsageSummaryResponse,
    WebhookDeliveryAttemptResponse,
    WebhookDeliveryResponse,
    WebhookEndpointCreatedResponse,
    WebhookEndpointResponse,
)
from ._pagination import CursorPage, OffsetPage, Page
from ._transport import RateLimitMeta, RequestOptions, ResponseMeta
from ._webhooks import (
    DEFAULT_TOLERANCE_SECONDS,
    SIGNATURE_HEADER,
    construct_event,
    sign_payload,
    signature_header_for,
)

__all__ = [
    # The client
    "PaymentFlow",
    # Identity
    "API_VERSION",
    "DEFAULT_BASE_URL",
    "USER_AGENT",
    "VERSION",
    # Configuration and the request pipeline
    "PaymentFlowConfigurationError",
    "RateLimitMeta",
    "RequestOptions",
    "ResponseMeta",
    # Pagination
    "CursorPage",
    "OffsetPage",
    "Page",
    # Errors
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
    # Webhooks
    "DEFAULT_TOLERANCE_SECONDS",
    "SIGNATURE_HEADER",
    "construct_event",
    "sign_payload",
    "signature_header_for",
    "webhooks",
    # The contract's data shapes
    "AnalyticsBucketResponse",
    "AnalyticsSummaryResponse",
    "ApiFieldError",
    "BalanceResponse",
    "BalanceTransactionResponse",
    "CurrencyBalance",
    "DecisionLogEntryResponse",
    "EventResponse",
    "PaymentResponse",
    "RefundResponse",
    "RequestLogResponse",
    "SimulationOverrideResponse",
    "TestCardResponse",
    "UsageBucketResponse",
    "UsageSummaryResponse",
    "WebhookDeliveryAttemptResponse",
    "WebhookDeliveryResponse",
    "WebhookEndpointCreatedResponse",
    "WebhookEndpointResponse",
]

#: This package's own version, which moves on its own schedule.
#:
#: Deliberately *not* the API revision. §7.3 pins SDK semver as independent of the dated
#: contract version: an SDK bug fix is a patch release against an unchanged API, and a new API
#: revision does not by itself change anything about this package.
VERSION: Final[str] = _VERSION

#: The dated API revision this build was generated against, sent as ``PaymentFlow-Version``.
API_VERSION: Final[str] = _API_VERSION

#: The host the client calls unless a ``base_url`` option overrides it.
DEFAULT_BASE_URL: Final[str] = _DEFAULT_BASE_URL
