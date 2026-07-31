"""``client.payments`` — the payment lifecycle (M22.6).

Seven methods, one per published operation, and no eighth. There is no ``create_and_capture``
convenience here even though it would be two obvious lines, because a method that performs two
chargeable calls behind one name is a method whose failure modes an integrator cannot reason
about: the second call failing leaves an authorized payment they did not know they had. Every
method here is exactly one HTTP request.
"""

from __future__ import annotations

from typing import Any, Mapping, Optional

from .._generated.models import PaymentResponse
from .._generated.operations import OPERATIONS
from .._pagination import CursorPage
from .._transport import RequestOptions
from ._base import Resource, body_of, query_of

__all__ = ["Payments"]


class Payments(Resource):
    def create(
        self,
        *,
        amount_minor: int,
        currency: str,
        description: Optional[str] = None,
        payment_method_token: Optional[str] = None,
        metadata: Optional[Mapping[str, str]] = None,
        options: Optional[RequestOptions] = None,
    ) -> PaymentResponse:
        """Creates a payment. Generates an ``Idempotency-Key`` unless you supply one.

        ``amount_minor`` is an integer in the currency's minor unit: ``1000`` in ``USD`` is
        $10.00. There are no floating-point amounts anywhere in this API.

        It is required here even though the published schema does not list it under
        ``required``. The platform's own field is a primitive with a ``@Positive`` bound, so a
        body that omits it is rejected with a 400 every time — the document understates the
        requirement, and an SDK signature that copied it would let a caller write the one
        request the API always refuses (D170).
        """
        body = body_of(
            amountMinor=amount_minor,
            currency=currency,
            description=description,
            paymentMethodToken=payment_method_token,
            metadata=metadata,
        )
        result: PaymentResponse = self._send(OPERATIONS["createPayment"], body=body, options=options)
        return result

    def retrieve(
        self, payment_id: str, *, expand: Optional[str] = None, options: Optional[RequestOptions] = None
    ) -> PaymentResponse:
        """Retrieves one payment. The only expandable relation is ``refunds``."""
        result: PaymentResponse = self._send(
            OPERATIONS["getPayment"], path={"id": payment_id}, query=query_of(expand=expand), options=options
        )
        return result

    def list(
        self,
        *,
        limit: Optional[int] = None,
        starting_after: Optional[str] = None,
        status: Optional[str] = None,
        currency: Optional[str] = None,
        amount_min: Optional[int] = None,
        amount_max: Optional[int] = None,
        created_after: Optional[str] = None,
        created_before: Optional[str] = None,
        expand: Optional[str] = None,
        metadata: Optional[Mapping[str, str]] = None,
        options: Optional[RequestOptions] = None,
    ) -> CursorPage[PaymentResponse]:
        """Lists your payments, most recent first. The result paginates transparently.

        ``metadata`` is a containment filter, spelled ``metadata[key]=value`` on the wire;
        every named key must match.
        """
        query = query_of(
            limit=limit,
            starting_after=starting_after,
            status=status,
            currency=currency,
            amount_min=amount_min,
            amount_max=amount_max,
            created_after=created_after,
            created_before=created_before,
            expand=expand,
            metadata=metadata,
        )
        return self._list_cursor(OPERATIONS["listPayments"], query, options)

    def authorize(self, payment_id: str, *, options: Optional[RequestOptions] = None) -> PaymentResponse:
        """Authorizes a created payment, reserving the funds."""
        result: PaymentResponse = self._send(
            OPERATIONS["authorizePayment"], path={"id": payment_id}, options=options
        )
        return result

    def capture(self, payment_id: str, *, options: Optional[RequestOptions] = None) -> PaymentResponse:
        """Captures an authorized payment, moving the funds."""
        result: PaymentResponse = self._send(
            OPERATIONS["capturePayment"], path={"id": payment_id}, options=options
        )
        return result

    def refund(
        self,
        payment_id: str,
        *,
        amount_minor: Optional[int] = None,
        reason: Optional[str] = None,
        metadata: Optional[Mapping[str, str]] = None,
        options: Optional[RequestOptions] = None,
    ) -> PaymentResponse:
        """Refunds a captured payment, in full or in part.

        Returns the **payment**, not the refund — the refund is in the payment's ``refunds``
        list. That is what the endpoint returns, and reshaping it here would mean either a
        second request or a guess about which element is the new one.

        Omit ``amount_minor`` to refund everything still refundable.
        """
        body: Mapping[str, Any] = body_of(amountMinor=amount_minor, reason=reason, metadata=metadata)
        result: PaymentResponse = self._send(
            OPERATIONS["refundPayment"], path={"id": payment_id}, body=body, options=options
        )
        return result

    def void(self, payment_id: str, *, options: Optional[RequestOptions] = None) -> PaymentResponse:
        """Voids an authorized payment, releasing the funds without capturing them."""
        result: PaymentResponse = self._send(
            OPERATIONS["voidPayment"], path={"id": payment_id}, options=options
        )
        return result
