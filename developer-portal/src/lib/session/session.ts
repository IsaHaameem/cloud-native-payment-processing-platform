import 'server-only';

import { env } from '@/lib/env';

import { seal, unseal } from './seal';

/**
 * The session itself: what it holds, how it is spelled in a cookie, and when it is stale
 * (M23.2, D196).
 *
 * ── What is in it, and what is deliberately not ───────────────────────────────────────
 *
 * Every field is here because a request cannot be served without it:
 *
 * - `accessToken` / `accessExpiresAt` — the credential the gateway validates, and the only way
 *   to know it is worth sending. Storing the expiry rather than decoding the JWT on each request
 *   keeps the hot path free of base64 and JSON, and — more importantly — means the portal never
 *   *reads* a token it does not verify. Trusting an unverified `exp` to decide "is it worth
 *   trying" is safe; trusting one to decide "is this user authentic" would not be, and keeping
 *   the value in the sealed envelope means that mistake is not available to make.
 * - `refreshToken` / `refreshExpiresAt` — without the expiry the portal cannot distinguish "this
 *   will work" from "this is a week old and certainly revoked", and would spend a network call
 *   plus a rotation attempt to find out.
 * - `userId`, `email`, `roles` — the identity the shell renders and `requireRole` checks.
 * - `merchantId` — nullable, because "signed in" and "onboarded" are genuinely different states
 *   (M23.0 answers a merchant-less session with 403, not 401).
 * - `mode` — session state, not a preference: it selects the data plane every request reads.
 * - `createdAt` — bounds absolute session age independently of token lifetimes.
 * - `version` — lets a shape change invalidate old cookies deliberately rather than by decode
 *   accident.
 *
 * Not here: the user's name, their merchant's name, anything a screen displays. A cookie is sent
 * on every single request; putting display data in it spends bandwidth on every request to save
 * a query on a few, and it goes stale the moment the user edits it.
 *
 * ── Cookie attributes ─────────────────────────────────────────────────────────────────
 *
 * `httpOnly` — no script may read it, which is the whole point of D187.
 *
 * `secure` in production only. Locking it on in development would mean the cookie is silently
 * never set over `http://localhost`, and the resulting "login does nothing" is a bad afternoon.
 *
 * `sameSite: 'strict'` — the browser does not attach this cookie to *any* cross-site request,
 * which removes the entire class of cross-site request forgery before the application-level
 * defences in `lib/security` are reached. The cost is real and is accepted: following a link
 * from an email or a chat lands on `/login` even for a signed-in user, because the cookie is
 * withheld on the cross-site navigation that carried them there. `lax` would fix that and would
 * also re-admit forged top-level `GET`s; nothing in this portal requires it, and the guidance
 * for M23.2 was Strict unless the application demands otherwise. It does not.
 *
 * `path: '/'` — the marketing page needs to know whether to say "Dashboard" or "Sign in", so the
 * scope cannot be narrowed to the app group.
 *
 * `maxAge` — the refresh token's lifetime. A cookie outliving its credentials is a cookie that
 * produces a confusing failure instead of a clean redirect.
 */

/** Bumped when the shape below changes incompatibly. Old cookies then fail to load, by design. */
export const SESSION_VERSION = 1;

export const SESSION_COOKIE = 'pf_session';

/** Seven days, matching identity-service's `paymentflow.security.jwt.refresh-token-ttl`. */
export const SESSION_MAX_AGE_SECONDS = 7 * 24 * 60 * 60;

/**
 * The hard ceiling on one session's life regardless of refresh activity (30 days). Rotation can
 * otherwise extend a session indefinitely: each refresh mints a token good for another week, so
 * a browser left open renews forever and a compromise never ages out. This is the backstop that
 * makes "sign in again eventually" true.
 */
export const SESSION_ABSOLUTE_MAX_AGE_SECONDS = 30 * 24 * 60 * 60;

export type Mode = 'test' | 'live';

export interface Session {
  readonly version: number;
  readonly userId: string;
  readonly email: string;
  readonly roles: readonly string[];
  readonly accessToken: string;
  /** Epoch milliseconds. */
  readonly accessExpiresAt: number;
  readonly refreshToken: string;
  /** Epoch milliseconds. */
  readonly refreshExpiresAt: number;
  /** Absent until the user has onboarded a merchant — the state `requireMerchant` exists for. */
  readonly merchantId: string | undefined;
  readonly mode: Mode;
  /** Epoch milliseconds. */
  readonly createdAt: number;
}

/**
 * The subset a client component may receive.
 *
 * Its existence is the enforcement: a page hands `toPublicSession(session)` to the tree, so
 * passing a token to the browser requires deliberately reaching past this function rather than
 * merely forgetting to strip a field.
 */
export interface PublicSession {
  readonly userId: string;
  readonly email: string;
  readonly roles: readonly string[];
  readonly merchantId: string | undefined;
  readonly mode: Mode;
}

export function toPublicSession(session: Session): PublicSession {
  return {
    userId: session.userId,
    email: session.email,
    roles: session.roles,
    merchantId: session.merchantId,
    mode: session.mode,
  };
}

/**
 * The cookie attributes, in one place so the middleware and the route handlers cannot disagree
 * about them — a `secure` flag set on one write path and missed on another is exactly the kind
 * of defect that survives review.
 */
export function sessionCookieOptions(): {
  httpOnly: true;
  secure: boolean;
  sameSite: 'strict';
  path: '/';
  maxAge: number;
} {
  return {
    httpOnly: true,
    secure: env.isProduction,
    sameSite: 'strict',
    path: '/',
    maxAge: SESSION_MAX_AGE_SECONDS,
  };
}

/** Attributes for clearing. `maxAge: 0` plus an empty value, so a stale value cannot linger. */
export function clearedSessionCookieOptions(): ReturnType<typeof sessionCookieOptions> {
  return { ...sessionCookieOptions(), maxAge: 0 };
}

export function encodeSession(session: Session): Promise<string> {
  return seal(session);
}

/**
 * @returns the session, or `null` if the cookie is absent, tampered with, of a superseded shape,
 *          past its absolute age, or holding a refresh token that has certainly expired.
 *
 * The staleness checks live here rather than at the call sites so that "I have a `Session`" means
 * "this is usable", everywhere, without each caller re-deriving what usable means.
 */
export async function decodeSession(
  sealed: string | undefined,
  now: number = Date.now(),
): Promise<Session | null> {
  const session = await unseal<Session>(sealed);
  if (!session || !isSessionShaped(session)) return null;
  if (session.version !== SESSION_VERSION) return null;
  if (now - session.createdAt >= SESSION_ABSOLUTE_MAX_AGE_SECONDS * 1000) return null;
  // Past this point the refresh token cannot be rotated, so there is no route back to a valid
  // access token — the session is over even though the cookie is intact.
  if (session.refreshExpiresAt <= now) return null;
  return session;
}

/**
 * Structural validation after decryption.
 *
 * Redundant against tampering — GCM already proved this server sealed it — but not against
 * *ourselves*: a cookie sealed by an earlier build with a different shape decrypts perfectly and
 * would otherwise flow into the application as a `Session` with `undefined` where a string
 * belongs. `version` catches the intentional changes; this catches the ones nobody remembered to
 * bump it for.
 */
function isSessionShaped(value: unknown): value is Session {
  if (typeof value !== 'object' || value === null) return false;
  const s = value as Record<string, unknown>;
  return (
    typeof s.version === 'number' &&
    typeof s.userId === 'string' &&
    typeof s.email === 'string' &&
    Array.isArray(s.roles) &&
    s.roles.every((role) => typeof role === 'string') &&
    typeof s.accessToken === 'string' &&
    typeof s.accessExpiresAt === 'number' &&
    typeof s.refreshToken === 'string' &&
    typeof s.refreshExpiresAt === 'number' &&
    (s.merchantId === undefined || typeof s.merchantId === 'string') &&
    (s.mode === 'test' || s.mode === 'live') &&
    typeof s.createdAt === 'number'
  );
}

/**
 * How long before expiry an access token is treated as already expired.
 *
 * Two minutes, and generously so on purpose. The middleware is the only place that may persist a
 * rotated refresh token, so its job is to guarantee the token is still valid for the *whole* of
 * the request it is admitting — including a slow downstream call and a clock a little ahead of
 * identity-service's. A tight skew would push refreshes into the render, where the rotation
 * cannot be written back.
 */
export const ACCESS_TOKEN_SKEW_MS = 120_000;

export function accessTokenIsUsable(session: Session, now: number = Date.now()): boolean {
  return session.accessExpiresAt - ACCESS_TOKEN_SKEW_MS > now;
}
