/**
 * Refunds, and reading back what happened.
 *
 * Run: PAYMENTFLOW_API_KEY=sk_test_… npx tsx examples/06-refunds-and-reporting.ts
 *
 * `client.refunds` is read-only because the API is: a refund is created by refunding a
 * payment. Mirroring that as `refunds.create()` would be a second name for one endpoint, and
 * the two would disagree about what they return the moment either changed.
 */
import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env['PAYMENTFLOW_API_KEY'] });

async function main(): Promise<void> {
  const paymentId = process.argv[2] ?? '';

  // Issue the refund against the payment...
  const payment = await client.payments.refund(paymentId, {
    amountMinor: 500,
    reason: 'requested_by_customer',
    metadata: { ticket: 'SUP-991' },
  });
  console.log(`${payment.refundedAmountMinor} refunded of ${payment.amountMinor}`);

  // ...and read refunds back through their own resource.
  for await (const refund of await client.refunds.list({ payment: paymentId })) {
    console.log(`  ${refund.id} ${refund.amountMinor} ${refund.status} ${refund.reason ?? ''}`);
  }

  const first = (await client.refunds.list({ payment: paymentId, limit: 1 })).data[0];
  if (first?.id !== undefined) {
    const refund = await client.refunds.retrieve(first.id);
    console.log(`retrieved ${refund.id}, created ${refund.createdAt}`);
  }

  // ── What it did to the balance ────────────────────────────────────────────────────────

  const balance = await client.balance.retrieve();
  for (const currency of balance.balances ?? []) {
    console.log(`${currency.currency}: ${currency.availableMinor} available`);
  }

  for await (const entry of await client.balanceTransactions.list({ limit: 20 })) {
    console.log(`  ${entry.direction} ${entry.amountMinor} ${entry.currency}`);
  }

  // ── And what it did to the numbers ────────────────────────────────────────────────────

  const analytics = await client.analytics.retrievePaymentSummary({
    from: '2026-07-01T00:00:00Z',
    to: '2026-08-01T00:00:00Z',
  });
  console.log(`${analytics.capturedCount ?? 0} captured, ${analytics.failedCount ?? 0} failed`);
  console.log(`success rate: ${analytics.successRate ?? 'n/a'}`);
  console.log(`captured ${analytics.totalCapturedAmountMinor ?? 0}, refunded ${analytics.totalRefundedAmountMinor ?? 0}`);

  // Usage is metered per UTC day, so its window is calendar dates rather than instants.
  const usage = await client.usage.retrieve({ from: '2026-07-01', to: '2026-07-31' });
  console.log(`${usage.totalRequests ?? 0} API requests this window`);

  // Every one of the calls above has a row here, keyed by the requestId the SDK reports.
  for await (const log of await client.requestLogs.list({ status_code: 200, limit: 5 })) {
    console.log(`  ${log.method} ${log.path} -> ${log.statusCode} (${log.requestId})`);
  }
}

main().catch((error: unknown) => {
  console.error(error);
  process.exitCode = 1;
});
