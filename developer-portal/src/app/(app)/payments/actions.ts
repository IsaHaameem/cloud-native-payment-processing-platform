'use server';

import { revalidatePath } from 'next/cache';

import { callAs } from '@/lib/api/client';
import { AuthenticationError, PlatformError } from '@/lib/api/errors';
import type { OperationId } from '@/lib/api/transport';
import { GUARD_MESSAGES, guardFormRequest } from '@/lib/security/form-guard';
import { readSession } from '@/lib/session/require';

import type { PaymentActionState } from './action-state';

/**
 * The payment FSM transitions, as guarded Server Actions (frontend build).
 *
 * ── Why these are Server Actions and not the read proxy ───────────────────────────────
 *
 * `/api/platform/[operation]` resolves GETs only, deliberately, so that a money movement cannot
 * be reached by naming an operation. Authorize, capture, void and refund are real gateway routes
 * (`POST /v1/payments/{id}/…`), so the backend genuinely supports them — the only missing piece
 * was this wrapper, which adds the two things a mutation needs: the CSRF token and the origin
 * assertion from `guardFormRequest`, and a single idempotency key per logical attempt.
 *
 * ── The idempotency key is supplied by the caller ────────────────────────────────────
 *
 * The client generates one UUID when the confirm dialog opens and resubmits the *same* value on
 * a retry of that attempt, so a double-click or a network retry replays the original response
 * rather than performing a second charge. A fresh dialog is a fresh attempt with a fresh key.
 */

function fail(message: string, platform?: PlatformError): PaymentActionState {
  return {
    ok: false,
    error: message,
    code: platform?.code,
    requestId: platform?.requestId,
  };
}

async function transition(
  operationId: OperationId,
  formData: FormData,
  body?: unknown,
): Promise<PaymentActionState> {
  const refused = await guardFormRequest(formData.get('csrfToken'));
  if (refused) return fail(GUARD_MESSAGES[refused]);

  const session = await readSession();
  if (!session) return fail('Your session is no longer valid. Sign in again to continue.');

  const id = String(formData.get('id') ?? '').trim();
  if (id.length === 0) return fail('That payment could not be identified.');

  const idempotencyKey = String(formData.get('idempotencyKey') ?? '').trim() || undefined;

  try {
    await callAs<unknown>(operationId, {
      session,
      path: { id },
      ...(idempotencyKey ? { idempotencyKey } : {}),
      ...(body !== undefined ? { body } : {}),
    });
  } catch (error) {
    if (error instanceof AuthenticationError) {
      return fail('Your session is no longer valid. Sign in again to continue.');
    }
    if (error instanceof PlatformError) {
      // 409 IDEMPOTENCY_CONFLICT means "still processing" — the client offers a retry with the
      // same key. Everything else is surfaced with the platform's own words.
      return fail(error.message, error);
    }
    throw error;
  }

  revalidatePath('/payments/[id]', 'page');
  revalidatePath('/payments', 'page');
  return { ok: true, error: undefined, code: undefined, requestId: undefined };
}

export async function authorizePaymentAction(
  _previous: PaymentActionState,
  formData: FormData,
): Promise<PaymentActionState> {
  return transition('authorizePayment', formData);
}

export async function capturePaymentAction(
  _previous: PaymentActionState,
  formData: FormData,
): Promise<PaymentActionState> {
  return transition('capturePayment', formData);
}

export async function voidPaymentAction(
  _previous: PaymentActionState,
  formData: FormData,
): Promise<PaymentActionState> {
  return transition('voidPayment', formData);
}

export async function refundPaymentAction(
  _previous: PaymentActionState,
  formData: FormData,
): Promise<PaymentActionState> {
  const rawAmount = String(formData.get('amountMinor') ?? '').trim();
  const reason = String(formData.get('reason') ?? '').trim();

  // Omit the body entirely to refund the full remaining amount — the contract's documented
  // behaviour. Only send amountMinor when the user asked for a partial refund.
  const body: Record<string, unknown> = {};
  if (rawAmount.length > 0) {
    const amount = Number(rawAmount);
    if (!Number.isInteger(amount) || amount <= 0) {
      return fail('Enter a whole amount in minor units greater than zero.');
    }
    body.amountMinor = amount;
  }
  if (reason.length > 0) body.reason = reason.slice(0, 500);

  return transition('refundPayment', formData, Object.keys(body).length > 0 ? body : undefined);
}
