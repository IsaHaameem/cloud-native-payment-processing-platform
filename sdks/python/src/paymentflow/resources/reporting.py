"""``client.analytics``, ``client.usage`` and ``client.request_logs`` — the three reporting
surfaces (M22.6).

One module because each is a single operation and the three are read the same way; three
classes because they are three namespaces on the client and a caller should not have to know
they share a file.

Note the two different window spellings, which are the platform's rather than this SDK's:
``analytics`` takes RFC 3339 instants and ``usage`` takes calendar dates. Usage is metered per
UTC day, so a window with a time in it would imply a precision the meter does not have.
"""

from __future__ import annotations

from typing import Optional

from .._generated.models import AnalyticsSummaryResponse, RequestLogResponse, UsageSummaryResponse
from .._generated.operations import OPERATIONS
from .._pagination import CursorPage
from .._transport import RequestOptions
from ._base import Resource, query_of

__all__ = ["Analytics", "RequestLogs", "Usage"]


class Analytics(Resource):
    def retrieve_payment_summary(
        self,
        *,
        from_: Optional[str] = None,
        to: Optional[str] = None,
        options: Optional[RequestOptions] = None,
    ) -> AnalyticsSummaryResponse:
        """Summarizes payment activity over a window, with hourly buckets.

        ``from_`` carries the trailing underscore PEP 8 prescribes for a keyword collision;
        ``from`` is a Python keyword and cannot be a parameter name. It is sent as ``from``,
        which is what the API expects.
        """
        query = query_of(**{"from": from_, "to": to})
        result: AnalyticsSummaryResponse = self._send(
            OPERATIONS["getPaymentAnalytics"], query=query, options=options
        )
        return result


class Usage(Resource):
    def retrieve(
        self,
        *,
        from_: Optional[str] = None,
        to: Optional[str] = None,
        options: Optional[RequestOptions] = None,
    ) -> UsageSummaryResponse:
        """Retrieves your API usage, metered per UTC day.

        The window is calendar dates (``YYYY-MM-DD``), not instants. See :class:`Analytics` for
        why ``from_`` is spelled that way.
        """
        query = query_of(**{"from": from_, "to": to})
        result: UsageSummaryResponse = self._send(OPERATIONS["getUsage"], query=query, options=options)
        return result


class RequestLogs(Resource):
    def list(
        self,
        *,
        limit: Optional[int] = None,
        starting_after: Optional[str] = None,
        created_after: Optional[str] = None,
        created_before: Optional[str] = None,
        status_code: Optional[int] = None,
        method: Optional[str] = None,
        options: Optional[RequestOptions] = None,
    ) -> CursorPage[RequestLogResponse]:
        """Lists your API calls, most recent first.

        Each row is keyed by the ``request_id`` this SDK reports on every response, so a call
        you captured in your own logs can be looked up here directly.
        """
        query = query_of(
            limit=limit,
            starting_after=starting_after,
            created_after=created_after,
            created_before=created_before,
            status_code=status_code,
            method=method,
        )
        return self._list_cursor(OPERATIONS["listRequestLogs"], query, options)
