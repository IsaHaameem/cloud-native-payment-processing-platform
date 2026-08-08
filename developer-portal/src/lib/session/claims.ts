/**
 * Reading the identity claims out of an access token (M23.2).
 *
 * ── This does not verify anything, and the naming says so ─────────────────────────────
 *
 * The gateway verifies the signature, the issuer and the expiry against identity-service's JWKS
 * on every single request. The portal repeating that would mean fetching and caching a key set
 * to re-derive an answer it is about to be given authoritatively — and would create a second
 * place where "is this token valid" is decided, which is one more than a system should have.
 *
 * What the portal needs is the *user's own* email and roles to render their own chrome, from a
 * token this server received directly from identity-service over TLS moments earlier and then
 * sealed in a cookie only this server can open. The trust comes from provenance, not from the
 * signature — and nothing security-relevant is decided from these values: `requireRole` gates
 * navigation, while every actual authorization decision is made downstream from the signed
 * internal context the gateway mints.
 *
 * The function is called `readUnverifiedClaims` so that a future caller tempted to authenticate
 * with it has to type the word "unverified" to do so.
 */

export interface AccessTokenClaims {
  readonly userId: string;
  readonly email: string;
  readonly roles: readonly string[];
  /** Epoch milliseconds, or `undefined` when the token carries no `exp`. */
  readonly expiresAt: number | undefined;
}

function decodeSegment(segment: string): unknown {
  const padded = segment.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return JSON.parse(new TextDecoder().decode(bytes));
}

/**
 * @returns the claims, or `null` if the token is not a readable JWT.
 *
 * A `null` here means the portal cannot describe the user it just authenticated, which is a
 * reason to refuse the login: a session whose `userId` is unknown cannot be reasoned about, and
 * inventing a placeholder would put a lie in the cookie.
 */
export function readUnverifiedClaims(accessToken: string): AccessTokenClaims | null {
  const parts = accessToken.split('.');
  if (parts.length !== 3) return null;

  let payload: unknown;
  try {
    payload = decodeSegment(parts[1] as string);
  } catch {
    return null;
  }
  if (typeof payload !== 'object' || payload === null) return null;

  const claims = payload as Record<string, unknown>;
  // `sub` is the user's UUID — identity-service's `JwtService` sets it from `user.getId()`, and
  // M23.0's gateway filter refuses any token whose subject is not a UUID.
  if (typeof claims.sub !== 'string' || claims.sub.length === 0) return null;

  const roles = Array.isArray(claims.roles)
    ? claims.roles.filter((role): role is string => typeof role === 'string')
    : [];

  return {
    userId: claims.sub,
    email: typeof claims.email === 'string' ? claims.email : '',
    roles,
    expiresAt: typeof claims.exp === 'number' ? claims.exp * 1000 : undefined,
  };
}
