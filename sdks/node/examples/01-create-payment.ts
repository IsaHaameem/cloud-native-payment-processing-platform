/**
 * Create a payment.
 *
 * Run: PAYMENTFLOW_API_KEY=sk_test_… npx tsx examples/01-create-payment.ts
 *
 * These examples are type-checked by `npm run typecheck:examples` against the same public
 * entry point an integrator installs, so an API change that broke them fails the build rather
 * than being discovered by someone copying from the README.
 */
import { PaymentFlow, type PaymentResponse } from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env['PAYMENTFLOW_API_KEY'] });

async function main(): Promise<void> {
  // Amounts are integers in the currency's minor unit. 1000 in USD is $10.00 — there are no
  // floating-point amounts anywhere in this API, deliberately.
  const payment: PaymentResponse = await client.payments.create({
    amountMinor: 1000,
    currency: 'USD',
    description: 'Order A-1234',
    metadata: { orderId: 'A-1234', channel: 'web' },
  });

  console.log(`created ${payment.id} for ${payment.amountMinor} ${payment.currency}`);
  console.log(`status: ${payment.status}`);

  // An Idempotency-Key was generated for that call and would have been reused had it been
  // retried. Pass your own when the retry has to survive *your* process restarting:
  await client.payments.create(
    { amountMinor: 2500, currency: 'USD' },
    { idempotencyKey: `order-A-1235` },
  );
}

main().catch((error: unknown) => {
  console.error(error);
  process.exitCode = 1;
});
