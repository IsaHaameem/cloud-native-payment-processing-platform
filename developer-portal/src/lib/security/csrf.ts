import 'server-only';

import { cookies } from 'next/headers';

import { env } from '@/lib/env';

/**
 * The synchronizer-token half of the CSRF defence (M23.2, D199).
 *
 * ── Why a synchronizer token and not double-submit ────────────────────────────────────
 *
 * The usual double-submit pattern puts the token in a cookie the page's own JavaScript reads and
 * echoes back. That requires a script-readable cookie, and the milestone's constraint is that no
 * secret is readable by script. So the token lives in an `httpOnly` cookie and is echoed by the
 * *server*, rendered into a hidden field of the form it protects. The browser never reads it;
 * only a page this server produced can carry a matching value.
 *
 * ── Why any of this, given `SameSite=Strict` and the origin check ─────────────────────
 *
 * These three defend different things and the login form is the case that proves it. Login
 * happens *before* a session cookie exists, so `SameSite` on the session cookie protects nothing
 * there. A forged cross-site login — signing a victim into the attacker's account, so that
 * everything the victim then does happens under credentials the attacker controls — is a real
 * attack that the origin check alone stops only as long as that header is present and correct.
 * A token the attacker cannot read is the defence that does not depend on a header.
 *
 * ── The comparison is timing-safe ─────────────────────────────────────────────────────
 *
 * `===` on strings short-circuits at the first differing byte. Over enough attempts that leaks
 * the prefix. The constant-time compare below costs nothing and removes the question.
 */

export const CSRF_COOKIE = 'pf_csrf';

export { CSRF_FIELD } from './csrf-field';

/** 32 bytes of CSPRNG output, hex-encoded. */
const TOKEN_BYTES = 32;

export class CsrfError extends Error {
  constructor() {
    super('This form has expired. Please try again.');
  }
}

function newToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(TOKEN_BYTES));
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

/** Mints a token. Exported for the middleware, which is where issuance happens. */
export function newCsrfToken(): string {
  return newToken();
}

export function isWellFormedCsrfToken(value: string | undefined): value is string {
  return typeof value === 'string' && value.length === TOKEN_BYTES * 2;
}

/** The cookie attributes, in one place so issuance and rotation cannot disagree. */
export function csrfCookieOptions(): {
  httpOnly: true;
  secure: boolean;
  sameSite: 'strict';
  path: '/';
} {
  return {
    httpOnly: true,
    secure: env.isProduction,
    sameSite: 'strict',
    path: '/',
    // Session-scoped: no `maxAge`, so it lives as long as the browser session. A CSRF token
    // outliving the browser buys nothing — the form it protects is gone.
  };
}

/**
 * @returns the token a form should carry, or `''` if there is none.
 *
 * Read-only, and that is a constraint rather than a style choice: this is called from Server
 * Components, which cannot set cookies. Issuance therefore happens in `middleware.ts`, which runs
 * before every render and owns a response — the same reason refresh lives there.
 *
 * Reusing the existing token rather than minting one per render is what lets two tabs hold two
 * working forms. A per-render token would invalidate whichever form the user did not submit
 * first, which is the classic broken-CSRF experience: "this form expired" on a page they just
 * loaded.
 */
export async function readCsrfToken(): Promise<string> {
  const store = await cookies();
  const existing = store.get(CSRF_COOKIE)?.value;
  return isWellFormedCsrfToken(existing) ? existing : '';
}

/**
 * @throws {CsrfError} if the submitted token is absent, malformed, or not the one in the cookie.
 */
export async function assertCsrfToken(
  submitted: FormDataEntryValue | null | undefined,
): Promise<void> {
  const store = await cookies();
  const expected = store.get(CSRF_COOKIE)?.value;

  if (typeof submitted !== 'string' || !expected) throw new CsrfError();
  if (!timingSafeEqual(submitted, expected)) throw new CsrfError();
}

/**
 * Rotates the token. Called after a successful login, because a token minted before
 * authentication and kept after it is a token an attacker may have fixed in the victim's browser
 * — the CSRF analogue of session fixation.
 */
export async function rotateCsrfToken(): Promise<void> {
  const store = await cookies();
  store.set(CSRF_COOKIE, newToken(), csrfCookieOptions());
}

export async function clearCsrfToken(): Promise<void> {
  const store = await cookies();
  store.set(CSRF_COOKIE, '', { ...csrfCookieOptions(), maxAge: 0 });
}

/**
 * Constant-time string comparison.
 *
 * The length is compared first and non-secretly, which is standard: token length is fixed and
 * public, so leaking "wrong length" leaks nothing. Everything after that runs in time
 * proportional to the length alone.
 */
export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let difference = 0;
  for (let i = 0; i < a.length; i++) {
    difference |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return difference === 0;
}
