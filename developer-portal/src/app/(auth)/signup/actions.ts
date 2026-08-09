'use server';

import { redirect } from 'next/navigation';

import { CsrfError, assertCsrfToken } from '@/lib/security/csrf';
import { CrossOriginRequestError } from '@/lib/security/origin';
import { safeRedirectPath } from '@/lib/security/redirect';
import {
  EmailAlreadyRegisteredError,
  IdentityUnavailableError,
  RegistrationRejectedError,
  register,
} from '@/lib/session/identity';
import { assertRequestIsSameOrigin } from '@/lib/session/lifecycle';

/**
 * The account-creation action (M23.2a).
 *
 * ── It creates an account and nothing else ────────────────────────────────────────────
 *
 * `POST /api/v1/auth/register` answers 201 with a `UserResponse` and no token pair, so there is
 * no session to establish here. The tempting shortcut — take the password the user just typed and
 * replay it into `login` — is declined deliberately: it turns one user action into two credential
 * submissions, doubles the surface on which a password can be logged or retried, and makes a
 * successful registration report failure whenever the *second* call is the one that fails. The
 * user is handed to `/login` with the address they registered and an explicit confirmation.
 *
 * ── The same three CSRF defences as sign-in, for the same reason ──────────────────────
 *
 * Next.js's Origin/Host comparison, this application's own {@link assertRequestIsSameOrigin},
 * and a synchronizer token from an `httpOnly` cookie (D199). Registration happens before any
 * session exists, so `SameSite` on the session cookie protects nothing here — exactly the gap
 * that makes the token the load-bearing defence on the entry pages.
 *
 * The token is **not** rotated on success. Rotation after sign-in exists because a token minted
 * before authentication and kept after it is the CSRF analogue of session fixation; registration
 * authenticates nobody, and rotating here would invalidate the login form the user is about to be
 * redirected to.
 *
 * ── Not throttled per account, and that is not an omission ────────────────────────────
 *
 * D200's throttle keys on the account being attacked, because the attack it stops is many
 * guesses at one account. Registration has no account to guess at; the abuse is bulk creation,
 * which is keyed by source and belongs to the gateway's limiter — the one place that sees the
 * source. A per-email throttle here would only tell an attacker which addresses exist.
 */

export interface SignupState {
  readonly error: string | undefined;
  /** Which field the message belongs to, so the form can mark it rather than only announce it. */
  readonly field: 'email' | 'password' | undefined;
}

const MESSAGES = {
  email_taken: 'An account with that email already exists. Sign in instead.',
  email_invalid: 'Enter a valid email address.',
  password_short: 'Use at least 8 characters.',
  password_long: 'Use at most 72 characters.',
  name_long: 'Use at most 150 characters.',
  rejected: 'Those details were not accepted. Check the address and try again.',
  unavailable: 'Account creation is temporarily unavailable. Please try again in a moment.',
  csrf: 'This form expired before it was submitted. Please try again.',
} as const;

/**
 * The constraints from `RegisterRequest`, restated so the user is told before a round trip
 * rather than after one. They are the platform's rules, not the portal's: 72 is BCrypt's input
 * limit, 150 is the `full_name` column.
 */
const PASSWORD_MIN = 8;
const PASSWORD_MAX = 72;
const NAME_MAX = 150;
const EMAIL_MAX = 255;

/** Deliberately permissive: the platform's `@Email` is authoritative, this only catches typos. */
const EMAIL_SHAPE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

export async function signupAction(
  _previous: SignupState,
  formData: FormData,
): Promise<SignupState> {
  try {
    await assertRequestIsSameOrigin();
    await assertCsrfToken(formData.get('csrfToken'));
  } catch (error) {
    if (error instanceof CsrfError || error instanceof CrossOriginRequestError) {
      return { error: MESSAGES.csrf, field: undefined };
    }
    throw error;
  }

  // Normalised the same way `AuthService.normalizeEmail` does, so what the user is told they
  // registered is what they can sign in with.
  const email = String(formData.get('email') ?? '')
    .trim()
    .toLowerCase();
  const password = String(formData.get('password') ?? '');
  const fullName = String(formData.get('fullName') ?? '').trim();

  if (!EMAIL_SHAPE.test(email) || email.length > EMAIL_MAX) {
    return { error: MESSAGES.email_invalid, field: 'email' };
  }
  if (password.length < PASSWORD_MIN) return { error: MESSAGES.password_short, field: 'password' };
  if (password.length > PASSWORD_MAX) return { error: MESSAGES.password_long, field: 'password' };
  if (fullName.length > NAME_MAX) return { error: MESSAGES.name_long, field: undefined };

  try {
    await register(email, password, fullName.length > 0 ? fullName : undefined);
  } catch (error) {
    if (error instanceof EmailAlreadyRegisteredError) {
      return { error: MESSAGES.email_taken, field: 'email' };
    }
    if (error instanceof RegistrationRejectedError) {
      return { error: MESSAGES.rejected, field: undefined };
    }
    if (error instanceof IdentityUnavailableError) {
      return { error: MESSAGES.unavailable, field: undefined };
    }
    throw error;
  }

  /*
   * `registered` is a flag, not a message: the login page renders fixed copy from it and never
   * echoes anything from this URL. `email` is carried so the user does not retype it — and is
   * re-validated on the way in there, because a query string is attacker-controlled even when
   * this action is what usually writes it.
   *
   * Any `next` the user arrived with survives the round trip, so someone who was bounced off a
   * deep link, registered, and then signed in still lands where they were originally going.
   */
  const params = new URLSearchParams({ registered: '1', email });
  const next = safeRedirectPath(String(formData.get('next') ?? ''), '');
  if (next) params.set('next', next);

  redirect(`/login?${params.toString()}`);
}
