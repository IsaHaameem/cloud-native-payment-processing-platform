/**
 * The full lifecycle: create, retrieve, authorize, capture, refund.
 *
 * Run: PAYMENTFLOW_API_KEY=sk_test_… npx tsx examples/02-payment-lifecycle.ts
 *
 * Each step is one HTTP request, deliberately. This SDK has no `createAndCapture` helper: a
 * method that made two chargeable calls behind one name would leave you an authorized payment
 * you did not know about whenever the second one failed.
 */
import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env['PAYMENTFLOW_API_KEY'] });

async function main(): Promise<void> {
  // `tok_visa_approved` is a seeded test card. `client.testHelpers.listCards()` lists them
  // all, along with what each one does.
  const created = await client.payments.create({
    amountMinor: 5000,
    currency: 'USD',
    paymentMethodToken: 'tok_visa_approved',
    description: 'Annual subscription',
  });
  console.log(`created   ${created.id} ${created.status}`);

  const retrieved = await client.payments.retrieve(created.id ?? '');
  console.log(`retrieved ${retrieved.id} ${retrieved.status}`);

  const authorized = await client.payments.authorize(created.id ?? '');
  console.log(`authorized ${authorized.status}, ${authorized.amountMinor} reserved`);

  const captured = await client.payments.capture(created.id ?? '');
  console.log(`captured  ${captured.capturedAmountMinor} of ${captured.amountMinor}`);

  // Partial refund. Omit `amountMinor` to refund everything still refundable.
  //
  // This returns the **payment**, not the refund — that is what the endpoint responds with,
  // and the new refund is in `payment.refunds`.
  const refunded = await client.payments.refund(created.id ?? '', {
    amountMinor: 1500,
    reason: 'requested_by_customer',
  });
  console.log(`refunded  ${refunded.refundedAmountMinor}, status ${refunded.status}`);
  console.log(`refunds:  ${(refunded.refunds ?? []).map((refund) => refund.id).join(', ')}`);

  // A payment that was authorized and should not be captured is voided instead, which
  // releases the reserved funds.
  const toVoid = await client.payments.create({ amountMinor: 800, currency: 'USD' });
  await client.payments.authorize(toVoid.id ?? '');
  const voided = await client.payments.void(toVoid.id ?? '');
  console.log(`voided    ${voided.id} ${voided.status}`);
}

main().catch((error: unknown) => {
  console.error(error);
  process.exitCode = 1;
});
