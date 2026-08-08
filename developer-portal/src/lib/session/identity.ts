import 'server-only';

import { env } from '@/lib/env';

/**
 * The portal's calls to identity-service (M23.2).
 *
 * Routed through the gateway rather than to identity-service directly, because `/api/v1/auth/**`
 * is a published gateway route (`permitAll` in the gateway's chain #2) and the portal is not
 * inside the service mesh. Going direct would mean a second base URL, a second network path, and
 * a portal that works in Compose and fails in a deployment where only the gateway is reachable.
 *
 * ── Every failure is classified, and none of them carries a token ─────────────────────
 *
 * The whole point of this module is that the rest of the portal never sees an HTTP status from
 * the auth path. It sees one of four outcomes, because those are the four the caller can act on:
 * bad credentials (tell the user), rejected token (end the session), unreachable (do not end the
 * session — it is our fault, not theirs), and unexpected (same, but log it).
 *
 * That third case is the one worth naming. If a backend blip were reported as "your token is
 * bad", the portal would sign everybody out during a deploy — turning a thirty-second outage
 * into a support queue.
 */

export class InvalidCredentialsError extends Error {}

/** identity-service rejected the refresh token: revoked, rotated already, or unknown. */
export class RejectedTokenError extends Error {}

/** The platform could not be reached, or answered 5xx. Never a reason to end a session. */
export class IdentityUnavailableError extends Error {}

export interface IssuedTokens {
  readonly accessToken: string;
  readonly refreshToken: string;
  /** Epoch milliseconds, computed from the response's `expiresIn`. */
  readonly accessExpiresAt: number;
}

/** One attempt may take this long. The gateway is one hop away. */
const TIMEOUT_MS = 10_000;

/**
 * identity-service's `AuthResponse`. Only the fields the portal uses are named; the shape is
 * validated before use because a 200 with an unexpected body must not become a session holding
 * `undefined` as its access token.
 */
interface AuthResponseBody {
  accessToken?: unknown;
  refreshToken?: unknown;
  expiresIn?: unknown;
}

export async function login(email: string, password: string): Promise<IssuedTokens> {
  const response = await post('/api/v1/auth/login', { email, password });

  if (response.status === 401 || response.status === 400) {
    // 400 covers bean-validation rejections — a malformed email, an empty password. Reported to
    // the user as "wrong credentials" rather than "malformed request": the distinction is of no
    // use to someone signing in, and answering differently would confirm which addresses exist.
    throw new InvalidCredentialsError('Those credentials were not accepted.');
  }
  return tokensFrom(response, 'login');
}

/**
 * Rotates the refresh token.
 *
 * Callers must reach this through `refreshSession` in `refresh.ts` and never directly:
 * rotation is single-use at the backend, so an uncoordinated call is how a refresh race becomes
 * a logout. See that module for why.
 */
export async function refresh(refreshToken: string): Promise<IssuedTokens> {
  const response = await post('/api/v1/auth/refresh', { refreshToken });

  if (response.status === 401 || response.status === 400) {
    throw new RejectedTokenError('The refresh token was not accepted.');
  }
  return tokensFrom(response, 'refresh');
}

/**
 * Revokes the refresh token. Best-effort by design.
 *
 * A logout that fails because identity-service is briefly unreachable must still clear the
 * cookie — refusing to sign the user out of their own browser because a backend is unwell is the
 * wrong trade. The access token remains valid for its remaining minutes either way, which is the
 * accepted cost of stateless access tokens and is why their TTL is fifteen minutes.
 *
 * @returns whether the server-side revocation actually happened, so the caller can log it.
 */
export async function logout(refreshToken: string): Promise<boolean> {
  try {
    const response = await post('/api/v1/auth/logout', { refreshToken });
    return response.status >= 200 && response.status < 300;
  } catch {
    return false;
  }
}

interface RawResponse {
  readonly status: number;
  readonly body: unknown;
}

async function post(path: string, body: unknown): Promise<RawResponse> {
  let response: Response;
  try {
    response = await fetch(`${env.gatewayUrl}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(body),
      cache: 'no-store',
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch (cause) {
    // The message is *not* forwarded: it can contain the request URL, and while these URLs carry
    // no token today, a rule of "never interpolate anything from the auth path into an error" is
    // one that keeps holding as the code changes.
    throw new IdentityUnavailableError(
      `The platform could not be reached (${cause instanceof Error ? cause.name : 'unknown'}).`,
    );
  }

  if (response.status >= 500) {
    throw new IdentityUnavailableError(`The platform answered ${response.status}.`);
  }

  const text = await response.text();
  let parsed: unknown;
  try {
    parsed = text.length > 0 ? JSON.parse(text) : undefined;
  } catch {
    parsed = undefined;
  }
  return { status: response.status, body: parsed };
}

function tokensFrom(response: RawResponse, operation: string): IssuedTokens {
  if (response.status < 200 || response.status >= 300) {
    throw new IdentityUnavailableError(`Unexpected ${response.status} from ${operation}.`);
  }

  const body = (response.body ?? {}) as AuthResponseBody;
  if (
    typeof body.accessToken !== 'string' ||
    typeof body.refreshToken !== 'string' ||
    typeof body.expiresIn !== 'number' ||
    body.accessToken.length === 0 ||
    body.refreshToken.length === 0
  ) {
    // A 200 that does not carry two tokens is not a session. Treated as unavailability rather
    // than as a rejected credential, because the caller's correct response is to keep the
    // existing session and try again — not to sign the user out over a shape change.
    throw new IdentityUnavailableError(`The ${operation} response did not carry tokens.`);
  }

  return {
    accessToken: body.accessToken,
    refreshToken: body.refreshToken,
    accessExpiresAt: Date.now() + body.expiresIn * 1000,
  };
}
