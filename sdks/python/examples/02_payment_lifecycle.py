"""The full lifecycle: create, retrieve, authorize, capture, refund, void.

Run: PAYMENTFLOW_API_KEY=sk_test_… python examples/02_payment_lifecycle.py

Each step is one HTTP request, deliberately. This SDK has no ``create_and_capture`` helper: a
method that made two chargeable calls behind one name would leave you an authorized payment you
did not know about whenever the second one failed.
"""

from __future__ import annotations

from paymentflow import PaymentFlow


def main() -> None:
    with PaymentFlow() as client:
        # `tok_visa_approved` is a seeded test card. `client.test_helpers.list_cards()` lists
        # them all, along with what each one does.
        created = client.payments.create(
            amount_minor=5000,
            currency="USD",
            payment_method_token="tok_visa_approved",
            description="Annual subscription",
        )
        payment_id = created.get("id", "")
        print(f"created    {payment_id} {created.get('status')}")

        retrieved = client.payments.retrieve(payment_id)
        print(f"retrieved  {retrieved.get('id')} {retrieved.get('status')}")

        authorized = client.payments.authorize(payment_id)
        print(f"authorized {authorized.get('status')}, {authorized.get('amountMinor')} reserved")

        captured = client.payments.capture(payment_id)
        print(f"captured   {captured.get('capturedAmountMinor')} of {captured.get('amountMinor')}")

        # Partial refund. Omit `amount_minor` to refund everything still refundable.
        #
        # This returns the **payment**, not the refund — that is what the endpoint responds
        # with, and the new refund is in `payment["refunds"]`.
        refunded = client.payments.refund(payment_id, amount_minor=1500, reason="requested_by_customer")
        print(f"refunded   {refunded.get('refundedAmountMinor')}, status {refunded.get('status')}")
        print("refunds:   " + ", ".join(str(r.get("id")) for r in refunded.get("refunds", [])))

        # A payment that was authorized and should not be captured is voided instead, which
        # releases the reserved funds.
        to_void = client.payments.create(amount_minor=800, currency="USD")
        client.payments.authorize(to_void.get("id", ""))
        voided = client.payments.void(to_void.get("id", ""))
        print(f"voided     {voided.get('id')} {voided.get('status')}")


if __name__ == "__main__":
    main()
