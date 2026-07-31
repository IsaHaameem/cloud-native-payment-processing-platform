"""Create a payment.

Run: PAYMENTFLOW_API_KEY=sk_test_… python examples/01_create_payment.py

These examples are type-checked by ``mypy`` as part of the package's own strict run, so an API
change that broke them fails the build rather than being discovered by someone copying from the
README.
"""

from __future__ import annotations

from paymentflow import PaymentFlow, PaymentResponse, RequestOptions


def main() -> None:
    client = PaymentFlow()

    # Amounts are integers in the currency's minor unit. 1000 in USD is $10.00 — there are no
    # floating-point amounts anywhere in this API, deliberately.
    payment: PaymentResponse = client.payments.create(
        amount_minor=1000,
        currency="USD",
        description="Order A-1234",
        metadata={"orderId": "A-1234", "channel": "web"},
    )

    print(f"created {payment.get('id')} for {payment.get('amountMinor')} {payment.get('currency')}")
    print(f"status: {payment.get('status')}")

    # An Idempotency-Key was generated for that call and would have been reused had it been
    # retried. Pass your own when the retry has to survive *your* process restarting:
    client.payments.create(
        amount_minor=2500,
        currency="USD",
        options=RequestOptions(idempotency_key="order-A-1235"),
    )

    client.close()


if __name__ == "__main__":
    main()
