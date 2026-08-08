'use server';

import { redirect } from 'next/navigation';

import { CsrfError, assertCsrfToken, rotateCsrfToken } from '@/lib/security/csrf';
import { CrossOriginRequestError } from '@/lib/security/origin';
import { safeRedirectPath } from '@/lib/security/redirect';
import {
  assertRequestIsSameOrigin,
  establishSession,
  persistSession,
} from '@/lib/session/lifecycle';

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
 * 2. {@link assertRequestIsSameOrigin} — this application's own check, so the guarantee does not
 *    rest solely on a framework default.
 * 3. {@link assertCsrfToken} — a token the attacker cannot read, from an `httpOnly` cookie.
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
  csrf: 'This form expired before it was submitted. Please try again.',
} as const;

export async function loginAction(_previous: LoginState, formData: FormData): Promise<LoginState> {
  try {
    await assertRequestIsSameOrigin();
    await assertCsrfToken(formData.get('csrfToken'));
  } catch (error) {
    if (error instanceof CsrfError || error instanceof CrossOriginRequestError) {
      return { error: MESSAGES.csrf };
    }
    throw error;
  }

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
