/**
 * Error handling.
 *
 * Run: PAYMENTFLOW_API_KEY=sk_test_… npx tsx examples/04-error-handling.ts
 *
 * Catching `PaymentFlowError` alone is already a complete, correct handler. Everything below
 * is narrowing from there, for cases where you can do something more useful than log.
 */
import {
  PaymentFlow,
  PaymentFlowError,
  AuthenticationError,
  PermissionError,
  InvalidRequestError,
  IdempotencyError,
  RateLimitError,
  ApiConnectionError,
  ApiError,
} from 'paymentflow';

const client = new PaymentFlow({ apiKey: process.env['PAYMENTFLOW_API_KEY'] });

async function capture(paymentId: string): Promise<void> {
  try {
    const payment = await client.payments.capture(paymentId);
    console.log(`captured ${payment.id}`);
  } catch (error: unknown) {
    if (error instanceof RateLimitError) {
      // The SDK already waited out anything short. Reaching here means the interval was
      // longer than it will block for — an exhausted daily quota clears at 00:00 UTC — so
      // schedule the work instead of retrying now.
      console.error(`rate limited; retry in ${error.retryAfterSeconds ?? '?'}s`);
      return;
    }

    if (error instanceof IdempotencyError) {
      // Distinct from InvalidRequestError despite sharing a 409: a concurrent request is
      // holding the same key, so this one *may* succeed later.
      console.error(`idempotency conflict (${error.code}); safe to try again shortly`);
      return;
    }

    if (error instanceof InvalidRequestError) {
      // A validation failure, an unknown id, or a state the payment cannot move from. It
      // will be rejected identically however many times it is sent.
      console.error(`rejected: ${error.code} ${error.message}`);
      if (error.param !== undefined) console.error(`  offending parameter: ${error.param}`);
      for (const field of error.fieldErrors ?? []) {
        console.error(`  ${field.field}: ${field.message}`);
      }
      if (error.docUrl !== undefined) console.error(`  ${error.docUrl}`);
      return;
    }

    if (error instanceof AuthenticationError) {
      console.error('the API key is missing, malformed, or revoked');
      return;
    }

    if (error instanceof PermissionError) {
      // Usually a missing scope, or a test key reaching for live data.
      console.error(`not permitted: ${error.message}`);
      return;
    }

    if (error instanceof ApiConnectionError) {
      // No response at all, so whether the request took effect is genuinely unknown. This is
      // exactly why mutations carry an idempotency key: retrying with the same one is safe.
      console.error(`no response after ${error.attempts ?? 1} attempt(s): ${error.message}`);
      return;
    }

    if (error instanceof ApiError) {
      // Not your fault. Quote the request id.
      console.error(`platform error ${error.statusCode}, request ${error.requestId}`);
      return;
    }

    if (error instanceof PaymentFlowError) {
      console.error(`${error.name}: ${error.message} (request ${error.requestId})`);
      return;
    }

    throw error;
  }
}

async function main(): Promise<void> {
  await capture('pay_does_not_exist');

  // Every response carries the identifiers needed to trace it, whether or not it failed —
  // `requestId` keys the matching row of `client.requestLogs.list()`.
  const page = await client.payments.list({ limit: 1 });
  console.log(`request ${page.meta.requestId}, revision ${page.meta.apiVersion}`);
  if (page.meta.rateLimit !== undefined) {
    console.log(`quota: ${page.meta.rateLimit.remaining} of ${page.meta.rateLimit.limit} left`);
  }
  if (page.meta.deprecated) {
    console.warn('this API revision is deprecated; see the Sunset header');
  }
}

main().catch((error: unknown) => {
  console.error(error);
  process.exitCode = 1;
});
