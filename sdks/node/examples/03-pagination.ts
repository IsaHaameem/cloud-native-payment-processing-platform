/**
 * Pagination, both ways.
 *
 * Run: PAYMENTFLOW_API_KEY=sk_test_… npx tsx examples/03-pagination.ts
 *
 * The ordinary thing — a `for await` over a list — is already the paginating thing. That is
 * deliberate: a helper you have to know to reach for is one most people will not reach for,
 * and their code processes only the first page for as long as their account is small enough
 * for that to look correct.
 */
import { PaymentFlow, type PaymentResponse } from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env['PAYMENTFLOW_API_KEY'] });

async function main(): Promise<void> {
  // ── Iterate everything ────────────────────────────────────────────────────────────────
  //
  // Fetches pages as it goes and never holds more than one in memory, so this is safe on an
  // account with a million payments.
  let total = 0;
  for await (const payment of await client.payments.list({ status: 'captured' })) {
    total += payment.amountMinor ?? 0;
  }
  console.log(`captured total: ${total}`);

  // Stopping early stops making requests — `break` does not quietly finish the account first.
  const recent: PaymentResponse[] = [];
  for await (const payment of await client.payments.list()) {
    recent.push(payment);
    if (recent.length === 10) break;
  }
  console.log(`ten most recent: ${recent.map((payment) => payment.id).join(', ')}`);

  // ── Or drive it by hand ───────────────────────────────────────────────────────────────
  //
  // The same object exposes the page directly, for a caller keeping their own cursor —
  // storing `nextCursor` between runs of a batch job, say.
  let page = await client.payments.list({ limit: 50, created_after: '2026-01-01T00:00:00Z' });
  while (true) {
    console.log(`page of ${page.data.length}, more: ${page.hasMore}`);
    const next = await page.nextPage();
    if (next === undefined) break;
    page = next;
  }

  // Filters carry across pages automatically. This one is a metadata containment filter,
  // spelled `metadata[orderId]=A-1234` on the wire; every named key must match.
  for await (const payment of await client.payments.list({ metadata: { channel: 'web' } })) {
    console.log(`web order ${payment.id}`);
  }

  // ── The other page shape ──────────────────────────────────────────────────────────────
  //
  // Webhook deliveries and sandbox decisions use offset pages rather than cursors, so they
  // report totals a cursor page deliberately does not. They iterate identically.
  const deliveries = await client.webhookDeliveries.list({ size: 20, sort: ['createdAt,desc'] });
  console.log(`${deliveries.totalElements} deliveries across ${deliveries.totalPages} pages`);
  for await (const delivery of deliveries) {
    console.log(`  ${delivery.id} ${delivery.status}`);
  }
}

main().catch((error: unknown) => {
  console.error(error);
  process.exitCode = 1;
});
