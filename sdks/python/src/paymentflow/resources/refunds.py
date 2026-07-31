"""``client.refunds`` — reading refunds (M22.6).

Read-only, because the API is. A refund is *created* through
:meth:`~paymentflow.resources.payments.Payments.refund`, which is where the platform puts it:
the operation is ``POST /v1/payments/{id}/refund`` and it returns the payment. Mirroring it
here as ``refunds.create()`` would be a second name for one endpoint, and the two would
disagree about what they return the moment either changed.
"""

from __future__ import annotations

from typing import Mapping, Optional

from .._generated.models import RefundResponse
from .._generated.operations import OPERATIONS
from .._pagination import CursorPage
from .._transport import RequestOptions
from ._base import Resource, query_of

__all__ = ["Refunds"]


class Refunds(Resource):
    def retrieve(self, refund_id: str, *, options: Optional[RequestOptions] = None) -> RefundResponse:
        """Retrieves one refund."""
        result: RefundResponse = self._send(OPERATIONS["getRefund"], path={"id": refund_id}, options=options)
        return result

    def list(
        self,
        *,
        limit: Optional[int] = None,
        starting_after: Optional[str] = None,
        payment: Optional[str] = None,
        status: Optional[str] = None,
        created_after: Optional[str] = None,
        created_before: Optional[str] = None,
        metadata: Optional[Mapping[str, str]] = None,
        options: Optional[RequestOptions] = None,
    ) -> CursorPage[RefundResponse]:
        """Lists your refunds, most recent first. The result paginates transparently."""
        query = query_of(
            limit=limit,
            starting_after=starting_after,
            payment=payment,
            status=status,
            created_after=created_after,
            created_before=created_before,
            metadata=metadata,
        )
        return self._list_cursor(OPERATIONS["listRefunds"], query, options)
