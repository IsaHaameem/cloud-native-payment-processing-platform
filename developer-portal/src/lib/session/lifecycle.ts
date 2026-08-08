import 'server-only';

import { headers } from 'next/headers';

import { assertSameOrigin } from '@/lib/security/origin';
import { isThrottled, recordFailure, recordSuccess } from '@/lib/security/login-throttle';

import { readUnverifiedClaims } from './claims';
import {
  IdentityUnavailableError,
  InvalidCredentialsError,
  login as loginAtIdentity,
  logout as logoutAtIdentity,
} from './identity';
import { resolveMerchantId } from './merchant';
import { type Session, SESSION_VERSION, SESSION_MAX_AGE_SECONDS } from './session';
import { clearSession, readSessionCookie, writeSession } from './store';

/**
 * Creating and destroying a session (M23.2).
 *
 * The two operations that change who the browser is, in one module, so that the ordering
 * constraints between them are visible together rather than spread across two route files.
 */

/** What a failed sign-in tells the caller. Never carries anything from the platform's response. */
export type LoginFailure = 'invalid_credentials' | 'throttled' | 'unavailable';

export type LoginResult = { ok: true; session: Session } | { ok: false; reason: LoginFailure };

function normalizeEmail(email: string): string {
  // The same normalisation identity-service applies (`AuthService.normalizeEmail`), so the
  // throttle counts `Ada@Example.com` and `ada@example.com` as attacks on one account rather
  // than two — which is otherwise a trivial way to multiply the allowance.
  return email.trim().toLowerCase();
}

/**
 * Signs a user in and returns the session, without writing any cookie.
 *
 * Separating "establish" from "persist" is what lets the caller decide the order of a redirect
 * and a `Set-Cookie`, and lets the tests exercise the whole flow without a request scope.
 */
export async function establishSession(email: string, password: string): Promise<LoginResult> {
  const normalized = normalizeEmail(email);

  if (isThrottled(normalized)) {
    // Reported as its own reason rather than as bad credentials: a user who has genuinely
    // forgotten their password deserves to be told to wait, not to keep guessing at a lock.
    return { ok: false, reason: 'throttled' };
  }

  let tokens;
  try {
    tokens = await loginAtIdentity(normalized, password);
  } catch (error) {
    if (error instanceof InvalidCredentialsError) {
      recordFailure(normalized);
      return { ok: false, reason: 'invalid_credentials' };
    }
    if (error instanceof IdentityUnavailableError) {
      // Deliberately not counted against the account: the failure was ours, and throttling a
      // user for our own outage would turn a blip into a lockout.
      return { ok: false, reason: 'unavailable' };
    }
    throw error;
  }

  const claims = readUnverifiedClaims(tokens.accessToken);
  if (!claims) {
    // identity-service issued something this portal cannot describe. Refused rather than
    // sessioned with placeholder identity — see `claims.ts`.
    return { ok: false, reason: 'unavailable' };
  }

  recordSuccess(normalized);

  const now = Date.now();
  return {
    ok: true,
    session: {
      version: SESSION_VERSION,
      userId: claims.userId,
      email: claims.email || normalized,
      roles: claims.roles,
      accessToken: tokens.accessToken,
      // The response's `expiresIn` is authoritative; the token's own `exp` is the fallback for a
      // response shape that ever stops carrying it.
      accessExpiresAt: tokens.accessExpiresAt || (claims.expiresAt ?? now),
      refreshToken: tokens.refreshToken,
      refreshExpiresAt: now + SESSION_MAX_AGE_SECONDS * 1000,
      merchantId: await resolveMerchantId(tokens.accessToken),
      // Always `test` on a fresh sign-in. A portal that remembered `live` across sessions is a
      // portal that shows a returning user live data before they have said so.
      mode: 'test',
      createdAt: now,
    },
  };
}

/**
 * Ends the session: revokes the refresh token server-side, then clears the cookie.
 *
 * Ordered that way on purpose. Clearing first and revoking after would leave a live refresh
 * token behind whenever the revocation failed, with nothing left in the browser to retry it
 * from. Revoking first means the worst case is a cleared cookie and a token that dies of old
 * age — a session the user has certainly left, rather than one they think they left.
 *
 * The access token stays valid for its remaining minutes either way. That is inherent to
 * stateless access tokens and is why identity-service keeps their TTL at fifteen minutes.
 *
 * @returns whether the server-side revocation succeeded. The cookie is cleared regardless.
 */
export async function destroySession(): Promise<boolean> {
  const session = await readSessionCookie();
  const revoked = session ? await logoutAtIdentity(session.refreshToken) : true;
  await clearSession();
  return revoked;
}

/** Persists a freshly established session. Only valid where a response is being built. */
export async function persistSession(session: Session): Promise<boolean> {
  return writeSession(session);
}

/**
 * The origin assertion, wired to the ambient request.
 *
 * Server Actions already carry Next.js's own Origin/Host comparison, so this is the second of
 * two independent checks rather than the only one. It is here because that built-in check is a
 * framework behaviour that a configuration change could alter, and because the Route Handlers in
 * this milestone are not covered by it at all.
 */
export async function assertRequestIsSameOrigin(): Promise<void> {
  assertSameOrigin(await headers());
}
