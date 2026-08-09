'use server';

import { redirect } from 'next/navigation';

import { rotateCsrfToken } from '@/lib/security/csrf';
import { GUARD_MESSAGES, guardFormRequest } from '@/lib/security/form-guard';
import { safeRedirectPath } from '@/lib/security/redirect';
import { establishSession, persistSession } from '@/lib/session/lifecycle';

/**
 * The sign-in action (M23.2).
 *
 * A Server Action rather than a Route Handler, for one concrete reason: the form works without
 * JavaScript. Next.js posts it to the server and the browser follows the redirect, so a user with
 * a blocked bundle or a failed hydration can still sign in — which for the *entry point* to an
 * application is worth more than it is for any screen behind it.
 *
 * ── Three CSRF defences, and why login needs all of them ──────────────────────────────
 *
 * 1. Next.js's own Origin/Host comparison for Server Actions.
 * 2. This application's own origin assertion, so the guarantee does not rest solely on a
 *    framework default.
 * 3. A synchronizer token the attacker cannot read, from an `httpOnly` cookie.
 *
 * The second and third are applied together by {@link guardFormRequest}, which also keeps their
 * two failures distinguishable — they were reported identically until M23.2b, and that is how a
 * portal reached on the wrong host came to say "this form expired".
 *
 * The third is the one that matters most *here*, and it is worth being precise about why: the
 * session cookie's `SameSite=Strict` protects requests that carry a session, and at login there
 * is none. The attack it stops is login CSRF — signing a victim into the *attacker's* account so
 * that everything they subsequently do happens under credentials the attacker can read. Nothing
 * about that attack requires the victim to have a session, which is exactly why the other two
 * defences are not sufficient on their own.
 */

export interface LoginState {
  readonly error: string | undefined;
}

/**
 * Messages are deliberately identical for "no such account" and "wrong password", and carry
 * nothing from the platform's response. Anything more specific is an account-enumeration oracle.
 */
const MESSAGES = {
  invalid_credentials: 'That email and password combination was not recognised.',
  throttled: 'Too many sign-in attempts. Please wait a few minutes and try again.',
  unavailable: 'Sign-in is temporarily unavailable. Please try again in a moment.',
} as const;

export async function loginAction(_previous: LoginState, formData: FormData): Promise<LoginState> {
  const refused = await guardFormRequest(formData.get('csrfToken'));
  if (refused) return { error: GUARD_MESSAGES[refused] };

  const email = String(formData.get('email') ?? '');
  const password = String(formData.get('password') ?? '');
  if (email.length === 0 || password.length === 0) {
    return { error: MESSAGES.invalid_credentials };
  }

  const result = await establishSession(email, password);
  if (!result.ok) {
    return { error: MESSAGES[result.reason] };
  }

  await persistSession(result.session);
  // A token issued before authentication must not survive it — the CSRF analogue of session
  // fixation, and one line to remove.
  await rotateCsrfToken();

  // Validated rather than trusted: `next` came from the query string. See `security/redirect.ts`.
  redirect(safeRedirectPath(String(formData.get('next') ?? '')));
}
