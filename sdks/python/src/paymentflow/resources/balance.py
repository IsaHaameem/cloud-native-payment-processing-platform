"""``client.balance`` and ``client.balance_transactions`` — where the money is, and how it got
there (M22.6).

Two namespaces rather than one because they are two resources with two shapes: a balance is a
single object you retrieve, and its transactions are a paginated list. Folding the list into
``balance.transactions()`` would read well and make ``client.balance`` an object that is
sometimes a value and sometimes a namespace.
"""

from __future__ import annotations

from typing import Optional

from .._generated.models import BalanceResponse, BalanceTransactionResponse
from .._generated.operations import OPERATIONS
from .._pagination import CursorPage
from .._transport import RequestOptions
from ._base import Resource, query_of

__all__ = ["Balance", "BalanceTransactions"]


class Balance(Resource):
    def retrieve(self, *, options: Optional[RequestOptions] = None) -> BalanceResponse:
        """Retrieves your current balance.

        One entry per currency you hold a balance in; a currency you have never transacted in
        is absent rather than reported as zero.
        """
        result: BalanceResponse = self._send(OPERATIONS["getBalance"], options=options)
        return result


class BalanceTransactions(Resource):
    def list(
        self,
        *,
        limit: Optional[int] = None,
        starting_after: Optional[str] = None,
        created_after: Optional[str] = None,
        created_before: Optional[str] = None,
        options: Optional[RequestOptions] = None,
    ) -> CursorPage[BalanceTransactionResponse]:
        """Lists the entries that moved your balance, most recent first."""
        query = query_of(
            limit=limit,
            starting_after=starting_after,
            created_after=created_after,
            created_before=created_before,
        )
        return self._list_cursor(OPERATIONS["listBalanceTransactions"], query, options)
