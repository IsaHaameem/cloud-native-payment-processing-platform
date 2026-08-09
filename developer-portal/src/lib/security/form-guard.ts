import 'server-only';

import { CsrfError, assertCsrfToken } from '@/lib/security/csrf';
import { CrossOriginRequestError } from '@/lib/security/origin';
import { assertRequestIsSameOrigin } from '@/lib/session/lifecycle';

/**
 * The two checks every state-changing form runs, and the two things they can say (M23.2b).
 *
 * ── Why this is one function instead of five copies ───────────────────────────────────
 *
 * Sign-in, sign-up, onboarding, password-reset request and password-reset confirm all open with
 * the same eight lines: assert the origin, assert the token, and map the two failures onto a
 * message. Written out per action, the sequence is easy to get subtly wrong in one place — a
 * check omitted, or the wrong message on the wrong error — and each mistake is invisible in the
 * diff that makes it, because the code still compiles and the happy path still works.
 *
 * Here it is one call, so a new form gets both defences by using the guard at all.
 *
 * ── The messages are deliberately different ───────────────────────────────────────────
 *
 * They were the same until M23.2b, and that is precisely how a configuration mistake — the
 * portal reached on `127.0.0.1` while configured for `localhost` — presented as *"this form
 * expired before it was submitted"* on a form rendered one second earlier. The user is entitled
 * to a message that describes what actually happened, and a developer is entitled to one that
 * points somewhere useful. Neither message says anything an attacker does not already know:
 * whoever forged the request knows their own origin, and whoever holds a stale token knows it is
 * stale.
 */

export type GuardFailure = 'csrf' | 'origin';

export const GUARD_MESSAGES: Record<GuardFailure, string> = {
  csrf: 'This form expired before it was submitted. Please try again.',
  origin: 'This request did not come from the portal. Reload the page and try again.',
};

/**
 * @returns the failure, or `undefined` when the request passed both checks.
 *
 * Returns rather than throws, because every caller is a Server Action whose job is to turn this
 * into a message in a form's error region — not to raise past it.
 */
export async function guardFormRequest(
  submittedToken: FormDataEntryValue | null,
): Promise<GuardFailure | undefined> {
  try {
    // Origin first. It is the cheaper check and the one whose failure explains the other: a
    // request from the wrong origin also tends to carry the wrong token, and reporting the token
    // is what sent this milestone's bug report chasing the wrong thing.
    await assertRequestIsSameOrigin();
    await assertCsrfToken(submittedToken);
  } catch (error) {
    if (error instanceof CrossOriginRequestError) return 'origin';
    if (error instanceof CsrfError) return 'csrf';
    throw error;
  }
  return undefined;
}
