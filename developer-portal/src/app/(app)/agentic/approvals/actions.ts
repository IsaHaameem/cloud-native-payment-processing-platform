'use server';

import { revalidatePath } from 'next/cache';

import { approveApproval, denyApproval } from '@/lib/agentic/operations';
import { AuthenticationError, PlatformError } from '@/lib/api/errors';
import { GUARD_MESSAGES, guardFormRequest } from '@/lib/security/form-guard';
import { readSession } from '@/lib/session/require';

import { APPROVAL_IDLE, type ApprovalActionState } from './action-state';

/**
 * The human end of the approval gate, as guarded Server Actions.
 *
 * `frontend_Design.md §19` — the highest-stakes screen. An approval decision is a mutation, so
 * it lives here rather than in a route handler: the CSRF token and origin assertion apply, and
 * the confirmation happens in the form.
 *
 * Neither action decides anything financial. `approve` tells `agentic-commerce-service` to
 * redeem the approval, and that service re-resolves the facts, re-evaluates policy and executes
 * — so the result of an approve is an agent turn. `deny` is terminal. The reviewer recorded on
 * the trail is the signed-in user; a caller cannot attribute a decision to someone else.
 */

function fail(message: string, platform?: PlatformError): ApprovalActionState {
  return { ...APPROVAL_IDLE, error: message, requestId: platform?.requestId };
}

async function guardedMerchantSession(csrfToken: FormDataEntryValue | null) {
  const refused = await guardFormRequest(csrfToken);
  if (refused) return { ok: false as const, state: fail(GUARD_MESSAGES[refused]) };

  const session = await readSession();
  if (!session) {
    return { ok: false as const, state: fail('Your session is no longer valid. Sign in again.') };
  }
  if (session.merchantId === undefined) {
    return { ok: false as const, state: fail('Finish onboarding before using agentic commerce.') };
  }
  return { ok: true as const, session: session as typeof session & { merchantId: string } };
}

function mapError(error: unknown): ApprovalActionState {
  if (error instanceof AuthenticationError) {
    return fail('Your session is no longer valid. Sign in again.');
  }
  if (error instanceof PlatformError) {
    return fail(error.message, error);
  }
  throw error;
}

export async function approveApprovalAction(
  _previous: ApprovalActionState,
  formData: FormData,
): Promise<ApprovalActionState> {
  const guarded = await guardedMerchantSession(formData.get('csrfToken'));
  if (!guarded.ok) return guarded.state;

  const approvalId = String(formData.get('approvalId') ?? '');
  if (approvalId === '')
    return fail('This approval could not be identified. Reload and try again.');

  try {
    const turn = await approveApproval(guarded.session, approvalId, guarded.session.email);
    revalidatePath('/agentic/approvals');
    revalidatePath(`/agentic/approvals/${approvalId}`);
    return { ok: true, error: undefined, requestId: undefined, turn, done: 'approved' };
  } catch (error) {
    return mapError(error);
  }
}

export async function denyApprovalAction(
  _previous: ApprovalActionState,
  formData: FormData,
): Promise<ApprovalActionState> {
  const guarded = await guardedMerchantSession(formData.get('csrfToken'));
  if (!guarded.ok) return guarded.state;

  const approvalId = String(formData.get('approvalId') ?? '');
  if (approvalId === '')
    return fail('This approval could not be identified. Reload and try again.');

  const rawReason = String(formData.get('reason') ?? '').trim();
  const reason = rawReason === '' ? null : rawReason.slice(0, 500);

  try {
    await denyApproval(guarded.session, approvalId, guarded.session.email, reason);
    revalidatePath('/agentic/approvals');
    revalidatePath(`/agentic/approvals/${approvalId}`);
    return { ok: true, error: undefined, requestId: undefined, turn: undefined, done: 'denied' };
  } catch (error) {
    return mapError(error);
  }
}
