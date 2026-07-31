"""Pagination, both ways.

Run: PAYMENTFLOW_API_KEY=sk_test_… python examples/03_pagination.py

The ordinary thing — a ``for`` over a list — is already the paginating thing. That is
deliberate: a helper you have to know to reach for is one most people will not reach for, and
their code processes only the first page for as long as their account is small enough for that
to look correct.
"""

from __future__ import annotations

from typing import List, Optional

from paymentflow import CursorPage, PaymentFlow, PaymentResponse


def main() -> None:
    with PaymentFlow() as client:
        # ── Iterate everything ────────────────────────────────────────────────────────────
        #
        # Fetches pages as it goes and never holds more than one in memory, so this is safe on
        # an account with a million payments.
        total = 0
        for payment in client.payments.list(status="captured"):
            total += payment.get("amountMinor", 0)
        print(f"captured total: {total}")

        # Stopping early stops making requests — `break` does not quietly finish the account.
        recent: List[PaymentResponse] = []
        for payment in client.payments.list():
            recent.append(payment)
            if len(recent) == 10:
                break
        print("ten most recent: " + ", ".join(str(p.get("id")) for p in recent))

        # ── Or drive it by hand ───────────────────────────────────────────────────────────
        #
        # The same object exposes the page directly, for a caller keeping their own cursor —
        # storing `next_cursor` between runs of a batch job, say.
        page: Optional[CursorPage[PaymentResponse]] = client.payments.list(
            limit=50, created_after="2026-01-01T00:00:00Z"
        )
        while page is not None:
            print(f"page of {len(page.data)}, more: {page.has_more}")
            page = page.next_page()

        # Filters carry across pages automatically. This one is a metadata containment filter,
        # spelled `metadata[orderId]=A-1234` on the wire; every named key must match.
        for payment in client.payments.list(metadata={"channel": "web"}):
            print(f"web order {payment.get('id')}")

        # ── The other page shape ──────────────────────────────────────────────────────────
        #
        # Webhook deliveries and sandbox decisions use offset pages rather than cursors, so
        # they report totals a cursor page deliberately does not. They iterate identically.
        deliveries = client.webhook_deliveries.list(size=20, sort=["createdAt,desc"])
        print(f"{deliveries.total_elements} deliveries across {deliveries.total_pages} pages")
        for delivery in deliveries:
            print(f"  {delivery.get('id')} {delivery.get('status')}")


if __name__ == "__main__":
    main()
