'use server';

import { GUARD_MESSAGES, guardFormRequest } from '@/lib/security/form-guard';
import { consumeResetAllowance } from '@/lib/security/reset-throttle';
import { IdentityUnavailableError, requestPasswordReset } from '@/lib/session/identity';

/**
 * Requesting a password-reset link (M23.2b).
 *
 * ── The success state does not mean an email was sent ─────────────────────────────────
 *
 * It means the request was accepted, and the copy says exactly that: *if that address has an
 * account, a link is on its way*. That phrasing is not hedging — it is the only honest sentence
 * available, because this action genuinely does not know. `AuthController.requestPasswordReset`
 * answers 202 for every address, and `PasswordResetService.requestReset` does its work inside an
 * `ifPresent`. Claiming "we've sent you an email" would be a statement the server cannot make,
 * and the one time it is false is the time it matters: the user typo'd their address and is now
 * waiting for mail that will never arrive.
 *
 * ── Every path returns the same state ─────────────────────────────────────────────────
 *
 * Unknown address, known address, throttled, malformed — all `sent: true`. There is exactly one
 * exception, and it is deliberately *not* about the address: the platform being unreachable,
 * which is a fact about us and tells an attacker nothing about who has an account. Anything else
 * would rebuild the enumeration oracle identity-service refuses to be, one branch at a time.
 *
 * A malformed address is worth calling out. Rejecting it with "enter a valid email" would be
 * harmless — it reveals nothing — but it also has to be *validated the same way for everyone*,
 * and the browser's own `type="email"` already does that before submission. Anything that gets
 * past it is either a determined prober or a broken client, and neither deserves a different
 * screen from the ordinary user.
 */

export interface ForgotPasswordState {
  readonly sent: boolean;
  readonly error: string | undefined;
}

const MESSAGES = {
  unavailable: 'Password reset is temporarily unavailable. Please try again in a moment.',
} as const;

/** Deliberately permissive: identity-service's `@Email` is authoritative. */
const EMAIL_SHAPE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

export async function forgotPasswordAction(
  _previous: ForgotPasswordState,
  formData: FormData,
): Promise<ForgotPasswordState> {
  const refused = await guardFormRequest(formData.get('csrfToken'));
  if (refused) return { sent: false, error: GUARD_MESSAGES[refused] };

  // Normalised exactly as `AuthService.normalizeEmail` does, so the throttle counts
  // `Ada@Example.com` and `ada@example.com` as one mailbox rather than two allowances.
  const email = String(formData.get('email') ?? '')
    .trim()
    .toLowerCase();

  // Shape-checked but not *reported on*: a malformed address simply never reaches the platform,
  // and the caller is shown the same screen as everyone else.
  if (!EMAIL_SHAPE.test(email)) return { sent: true, error: undefined };

  if (!consumeResetAllowance(email)) return { sent: true, error: undefined };

  try {
    await requestPasswordReset(email);
  } catch (error) {
    if (error instanceof IdentityUnavailableError) {
      return { sent: false, error: MESSAGES.unavailable };
    }
    throw error;
  }

  return { sent: true, error: undefined };
}
