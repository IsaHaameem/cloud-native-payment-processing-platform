"""The ``PaymentFlow`` client (M22.5).

One object holding one resolved configuration and one transport, with the eleven resource
namespaces hanging off it. Constructed once per API key and shared: a client owns an
``httpx.Client``, so building one per request throws away connection reuse as well as
re-reading the environment and re-validating on every call.

The namespaces are created eagerly in ``__init__`` rather than behind ``@property``. They are
eleven object allocations, they make the client's shape visible to ``dir()`` and to an editor,
and a property that constructs on first access is a property that can raise from an attribute
read.
"""

from __future__ import annotations

from types import TracebackType
from typing import Optional, Type

import httpx

from . import _webhooks
from ._config import ResolvedConfig, resolve_config
from ._transport import Transport
from .resources.balance import Balance, BalanceTransactions
from .resources.events import Events
from .resources.payments import Payments
from .resources.refunds import Refunds
from .resources.reporting import Analytics, RequestLogs, Usage
from .resources.test_helpers import TestHelpers
from .resources.webhooks import WebhookDeliveries, WebhookEndpoints

__all__ = ["PaymentFlow"]


class PaymentFlow:
    """The PaymentFlow API client.

    ::

        from paymentflow import PaymentFlow

        client = PaymentFlow(api_key="sk_test_…")
        payment = client.payments.create(amount_minor=1000, currency="USD")

    Usable as a context manager, which closes the underlying HTTP client on exit::

        with PaymentFlow() as client:
            ...
    """

    def __init__(
        self,
        *,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        api_version: Optional[str] = None,
        timeout: Optional[float] = None,
        max_retries: Optional[int] = None,
        http_client: Optional[httpx.Client] = None,
    ) -> None:
        """
        :param api_key: your secret key. Falls back to ``PAYMENTFLOW_API_KEY``. The key alone
            decides both whose data you see and which mode you see it in.
        :param base_url: the host to call. Override for a local stack.
        :param api_version: the dated revision to send as ``PaymentFlow-Version``.
        :param timeout: seconds per HTTP attempt. Defaults to 30.
        :param max_retries: how many times a retryable failure is retried. Defaults to 3.
        :param http_client: an ``httpx.Client`` to use. Injectable for tests and for proxy
            configuration; one supplied here is yours to close.
        :raises PaymentFlowConfigurationError: if an option cannot work.
        """
        #: The configuration this client resolved, after defaults and validation.
        self.config: ResolvedConfig = resolve_config(
            api_key=api_key,
            base_url=base_url,
            api_version=api_version,
            timeout=timeout,
            max_retries=max_retries,
        )
        self._transport = Transport(self.config, http_client)

        #: Creating, reading and moving payments through their lifecycle.
        self.payments = Payments(self._transport)
        #: Reading refunds. They are created through ``payments.refund()``.
        self.refunds = Refunds(self._transport)
        #: Your current balance, per currency.
        self.balance = Balance(self._transport)
        #: The entries that moved your balance.
        self.balance_transactions = BalanceTransactions(self._transport)
        #: The event log behind your webhooks.
        self.events = Events(self._transport)
        #: Payment activity summarized over a window.
        self.analytics = Analytics(self._transport)
        #: Your API calls, as the platform recorded them.
        self.request_logs = RequestLogs(self._transport)
        #: Your API usage, metered per UTC day.
        self.usage = Usage(self._transport)
        #: Where events are delivered, and their signing secrets.
        self.webhook_endpoints = WebhookEndpoints(self._transport)
        #: What happened when an event was delivered.
        self.webhook_deliveries = WebhookDeliveries(self._transport)
        #: The sandbox controls. Test mode only, decided by your key.
        self.test_helpers = TestHelpers(self._transport)

        #: Verifying deliveries you receive.
        #:
        #: The one namespace that makes no HTTP requests and needs no API key — it is here for
        #: discoverability, and the same functions are exported from the package root so a
        #: receiver process that never calls the API does not have to construct a client.
        self.webhooks = _webhooks

    def close(self) -> None:
        """Closes the underlying HTTP client, if this client created it."""
        self._transport.close()

    def __enter__(self) -> "PaymentFlow":
        return self

    def __exit__(
        self,
        exc_type: Optional[Type[BaseException]],
        exc: Optional[BaseException],
        traceback: Optional[TracebackType],
    ) -> None:
        self.close()
