"""Refunds, and reading back what happened.

Run: PAYMENTFLOW_API_KEY=sk_test_… python examples/06_refunds_and_reporting.py pay_1

``client.refunds`` is read-only because the API is: a refund is created by refunding a payment.
Mirroring that as ``refunds.create()`` would be a second name for one endpoint, and the two
would disagree about what they return the moment either changed.
"""

from __future__ import annotations

import sys

from paymentflow import PaymentFlow


def main() -> None:
    payment_id = sys.argv[1] if len(sys.argv) > 1 else ""

    with PaymentFlow() as client:
        # Issue the refund against the payment...
        payment = client.payments.refund(
            payment_id, amount_minor=500, reason="requested_by_customer", metadata={"ticket": "SUP-991"}
        )
        print(f"{payment.get('refundedAmountMinor')} refunded of {payment.get('amountMinor')}")

        # ...and read refunds back through their own resource.
        for refund in client.refunds.list(payment=payment_id):
            print(f"  {refund.get('id')} {refund.get('amountMinor')} {refund.get('status')} {refund.get('reason', '')}")

        first = client.refunds.list(payment=payment_id, limit=1).data
        if first:
            one = client.refunds.retrieve(str(first[0].get("id")))
            print(f"retrieved {one.get('id')}, created {one.get('createdAt')}")

        # ── What it did to the balance ────────────────────────────────────────────────────

        balance = client.balance.retrieve()
        for currency in balance.get("balances", []):
            print(f"{currency.get('currency')}: {currency.get('availableMinor')} available")

        for entry in client.balance_transactions.list(limit=20):
            print(f"  {entry.get('direction')} {entry.get('amountMinor')} {entry.get('currency')}")

        # ── And what it did to the numbers ────────────────────────────────────────────────

        analytics = client.analytics.retrieve_payment_summary(
            from_="2026-07-01T00:00:00Z", to="2026-08-01T00:00:00Z"
        )
        print(f"{analytics.get('capturedCount', 0)} captured, {analytics.get('failedCount', 0)} failed")
        print(f"success rate: {analytics.get('successRate')}")

        # Usage is metered per UTC day, so its window is calendar dates rather than instants.
        usage = client.usage.retrieve(from_="2026-07-01", to="2026-07-31")
        print(f"{usage.get('totalRequests', 0)} API requests this window")

        # Every one of the calls above has a row here, keyed by the request_id the SDK reports.
        for log in client.request_logs.list(status_code=200, limit=5):
            print(f"  {log.get('method')} {log.get('path')} -> {log.get('statusCode')} ({log.get('requestId')})")


if __name__ == "__main__":
    main()
