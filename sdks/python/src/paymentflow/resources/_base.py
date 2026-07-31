"""What every resource namespace shares (M22.6).

Resource classes are thin on purpose: a method names an operation, hands over its parameters,
and returns what the API returned. Everything that could differ between them — how a path is
filled in, which query parameters are legal, when an idempotency key is generated, what gets
retried — lives in the transport, so that adding an endpoint cannot accidentally add a
behaviour.

Two Python-specific notes.

Methods take **keyword arguments** in ``snake_case``, because that is how a Python API is
spelled, and each method builds the request body with the contract's own field names written
out explicitly (D179). The mapping is therefore local, visible and type-checked at every call
site rather than performed by a runtime name-mangler — and ``tests/test_resources.py`` asserts
that every key any method sends exists in the contract's request models, so it cannot drift.

Query parameters are already ``snake_case`` on the wire (``starting_after``, ``created_after``),
so those pass straight through and the two halves of the API agree in Python.
"""

from __future__ import annotations

from typing import Any, Dict, Mapping, Optional, Tuple, TypeVar

from .._generated.operations import OperationDescriptor
from .._pagination import CursorPage, OffsetPage
from .._transport import RequestOptions, RequestSpec, ResponseMeta, Transport

T = TypeVar("T")


def body_of(**fields: Any) -> Dict[str, Any]:
    """Drops the fields a caller did not supply.

    Sending ``{"description": null}`` is not the same request as omitting it — the platform
    reads the first as "set this to nothing". Every optional parameter defaults to ``None``
    here, so the two have to be distinguished at exactly one place, and this is it.
    """
    return {name: value for name, value in fields.items() if value is not None}


def query_of(**fields: Any) -> Dict[str, Any]:
    """The same, for query strings. ``None`` means "do not filter on this"."""
    return {name: value for name, value in fields.items() if value is not None}


class Resource:
    """The base every namespace extends."""

    def __init__(self, transport: Transport) -> None:
        self._transport = transport

    # ── One call ────────────────────────────────────────────────────────────────────────

    def _send(
        self,
        operation: OperationDescriptor,
        *,
        path: Optional[Mapping[str, str]] = None,
        query: Optional[Mapping[str, Any]] = None,
        body: Optional[Mapping[str, Any]] = None,
        options: Optional[RequestOptions] = None,
    ) -> Any:
        result = self._transport.request(
            RequestSpec(
                operation=operation,
                path=dict(path or {}),
                query=dict(query or {}),
                body=body,
                options=options,
            )
        )
        return result.data

    # ── Paginated calls ─────────────────────────────────────────────────────────────────

    def _list_cursor(
        self,
        operation: OperationDescriptor,
        query: Mapping[str, Any],
        options: Optional[RequestOptions],
    ) -> CursorPage[Any]:
        """A cursor-paginated list.

        The closure captures the caller's filters so that every subsequent page is fetched with
        the same ones. Re-issuing a page request with different filters than the cursor was
        minted under is the classic way an auto-paginating client returns a result set that
        never existed.
        """
        filters = dict(query)

        def fetch(cursor: Optional[str]) -> Tuple[Dict[str, Any], ResponseMeta]:
            page = dict(filters)
            if cursor is not None:
                page["starting_after"] = cursor
            result = self._transport.request(
                RequestSpec(operation=operation, query=page, options=options)
            )
            body: Dict[str, Any] = result.data if isinstance(result.data, dict) else {}
            return body, result.meta

        first, meta = fetch(None)
        return CursorPage(first, meta, fetch)

    def _list_offset(
        self,
        operation: OperationDescriptor,
        query: Mapping[str, Any],
        options: Optional[RequestOptions],
        path: Optional[Mapping[str, str]] = None,
    ) -> OffsetPage[Any]:
        """An offset-paginated list — the two endpoints D139 left on the older envelope."""
        filters = dict(query)
        requested = filters.get("page")

        def fetch(index: int) -> Tuple[Dict[str, Any], ResponseMeta]:
            page = dict(filters)
            page["page"] = index
            result = self._transport.request(
                RequestSpec(operation=operation, path=dict(path or {}), query=page, options=options)
            )
            body: Dict[str, Any] = result.data if isinstance(result.data, dict) else {}
            return body, result.meta

        first, meta = fetch(requested if isinstance(requested, int) else 0)
        return OffsetPage(first, meta, fetch)
