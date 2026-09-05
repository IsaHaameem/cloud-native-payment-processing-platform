'use server';

import { revalidatePath } from 'next/cache';

import { callAs } from '@/lib/api/client';
import { AuthenticationError, PlatformError } from '@/lib/api/errors';
import { GUARD_MESSAGES, guardFormRequest } from '@/lib/security/form-guard';
import { readSession } from '@/lib/session/require';

import type { SandboxActionState } from './action-state';

/**
 * Sandbox simulation-override mutations, as guarded Server Actions (frontend build).
 *
 * `frontend_Design.md §17` / Developer Platform: `POST /v1/test/simulations` and
 * `DELETE /v1/test/simulations/active`. Test-only routes, so they carry the same CSRF + origin
 * guard as every other mutation.
 */

function fail(message: string, platform?: PlatformError): SandboxActionState {
  return { ok: false, error: message, requestId: platform?.requestId };
}

export async function createSimulationOverrideAction(
  _prev: SandboxActionState,
  formData: FormData,
): Promise<SandboxActionState> {
  const refused = await guardFormRequest(formData.get('csrfToken'));
  if (refused) return fail(GUARD_MESSAGES[refused]);

  const session = await readSession();
  if (!session) return fail('Your session is no longer valid. Sign in again.');

  const scenario = String(formData.get('scenario') ?? '').trim();
  if (!scenario) return fail('Choose a scenario.');

  const body: Record<string, unknown> = { scenario };
  const num = (name: string) => {
    const raw = String(formData.get(name) ?? '').trim();
    if (raw.length === 0) return undefined;
    const n = Number(raw);
    return Number.isFinite(n) ? n : undefined;
  };
  const declineCode = String(formData.get('declineCode') ?? '').trim();
  const errorCode = String(formData.get('errorCode') ?? '').trim();
  if (declineCode) body.declineCode = declineCode;
  if (errorCode) body.errorCode = errorCode;
  const latency = num('latencyMs');
  const remaining = num('remainingCount');
  const duration = num('durationSeconds');
  if (latency !== undefined) body.latencyMs = latency;
  if (remaining !== undefined) body.remainingCount = remaining;
  if (duration !== undefined) body.durationSeconds = duration;

  try {
    await callAs('createSimulationOverride', { session, body });
  } catch (error) {
    if (error instanceof AuthenticationError) {
      return fail('Your session is no longer valid. Sign in again.');
    }
    if (error instanceof PlatformError) return fail(error.message, error);
    throw error;
  }

  revalidatePath('/developers/sandbox', 'page');
  return { ok: true, error: undefined, requestId: undefined };
}

export async function revokeSimulationOverrideAction(
  _prev: SandboxActionState,
  formData: FormData,
): Promise<SandboxActionState> {
  const refused = await guardFormRequest(formData.get('csrfToken'));
  if (refused) return fail(GUARD_MESSAGES[refused]);

  const session = await readSession();
  if (!session) return fail('Your session is no longer valid. Sign in again.');

  try {
    await callAs('revokeActiveSimulationOverride', { session });
  } catch (error) {
    if (error instanceof AuthenticationError) {
      return fail('Your session is no longer valid. Sign in again.');
    }
    if (error instanceof PlatformError) return fail(error.message, error);
    throw error;
  }

  revalidatePath('/developers/sandbox', 'page');
  return { ok: true, error: undefined, requestId: undefined };
}
