'use server';

import { redirect } from 'next/navigation';

import { GUARD_MESSAGES, guardFormRequest } from '@/lib/security/form-guard';
import {
  IdentityUnavailableError,
  InvalidResetTokenError,
  RegistrationRejectedError,
  confirmPasswordReset,
} from '@/lib/session/identity';

/**
 * Setting a new password from a reset token (M23.2b).
 *
 * ── The token travels in a hidden field, not in the action's closure ──────────────────
 *
 * It arrived in the query string, the page read it there, and it goes back to the server in the
 * form. That is the same path the CSRF token takes and it is chosen for the same reason: the form
 * has to work without JavaScript, and a value captured in a Server Action's closure requires the
 * client runtime to have loaded in order to be sent.
 *
 * It is deliberately **not** put in a cookie or in session storage on the way past. A reset token
 * is a credential; the fewer places it is written, the fewer places it is left.
 *
 * ── Success does not sign the user in ─────────────────────────────────────────────────
 *
 * `PasswordResetService.confirmReset` calls `refreshTokenRepository.revokeAllForUser`, so at the
 * moment this returns the account has no valid sessions anywhere — which is the correct
 * behaviour for a password change and the reason the flow ends at `/login` rather than at the
 * dashboard. It is also consistent with D201: the portal does not manufacture a session the
 * platform did not issue.
 */

export interface ResetPasswordState {
  readonly error: string | undefined;
  readonly field: 'password' | 'confirm' | undefined;
}

const MESSAGES = {
  invalid_token:
    'That reset link is no longer valid. It may have expired or already been used — request a new one.',
  password_short: 'Use at least 8 characters.',
  password_long: 'Use at most 72 characters.',
  mismatch: 'Those passwords do not match.',
  rejected: 'That password was not accepted. Try a different one.',
  unavailable: 'Password reset is temporarily unavailable. Please try again in a moment.',
} as const;

/** identity-service's own bounds, from `PasswordResetConfirmRequest`. 72 is BCrypt's limit. */
const PASSWORD_MIN = 8;
const PASSWORD_MAX = 72;

export async function resetPasswordAction(
  _previous: ResetPasswordState,
  formData: FormData,
): Promise<ResetPasswordState> {
  const refused = await guardFormRequest(formData.get('csrfToken'));
  if (refused) return { error: GUARD_MESSAGES[refused], field: undefined };

  const token = String(formData.get('token') ?? '');
  const password = String(formData.get('password') ?? '');
  const confirm = String(formData.get('confirm') ?? '');

  if (token.length === 0) return { error: MESSAGES.invalid_token, field: undefined };

  if (password.length < PASSWORD_MIN) return { error: MESSAGES.password_short, field: 'password' };
  if (password.length > PASSWORD_MAX) return { error: MESSAGES.password_long, field: 'password' };
  // Checked here rather than only in the browser: a confirmation field that is enforced by
  // JavaScript alone is not enforced, and this form is built to work without it.
  if (password !== confirm) return { error: MESSAGES.mismatch, field: 'confirm' };

  try {
    await confirmPasswordReset(token, password);
  } catch (error) {
    if (error instanceof InvalidResetTokenError) {
      return { error: MESSAGES.invalid_token, field: undefined };
    }
    if (error instanceof RegistrationRejectedError) {
      return { error: MESSAGES.rejected, field: 'password' };
    }
    if (error instanceof IdentityUnavailableError) {
      return { error: MESSAGES.unavailable, field: undefined };
    }
    throw error;
  }

  // A flag, not a message: the login page renders fixed copy from it and never echoes anything
  // from this URL. The address is not carried across — unlike registration, nothing here
  // established which address it was, and reading it back off the form would put an account
  // identifier in a URL for no gain.
  redirect('/login?reset=1');
}
