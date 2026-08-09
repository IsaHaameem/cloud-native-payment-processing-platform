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

/**
 * `POST /register` answered 409 — `EmailAlreadyExistsException`.
 *
 * ── This does leak whether an address has an account, and that is the backend's answer ──
 *
 * Login is carefully non-enumerable; registration cannot be, because the platform has to refuse
 * a second account on one address and the caller has to be told why. Inventing a fake success
 * would leave the user staring at a sign-in page for an account they believe they just made.
 * So the portal reports what identity-service reports, and pairs it with the useful next step —
 * a link to sign in — rather than pretending the request succeeded.
 */
export class EmailAlreadyRegisteredError extends Error {}

/** `POST /register` answered 400 — bean validation refused the payload. */
export class RegistrationRejectedError extends Error {}

/** identity-service rejected the refresh token: revoked, rotated already, or unknown. */
export class RejectedTokenError extends Error {}

/**
 * A password-reset token was refused: unknown, expired, or already used.
 *
 * The three are one error on purpose, and the backend agrees — `PasswordResetService.confirmReset`
 * throws the same `InvalidTokenException` for all of them, because telling them apart tells a
 * holder of a guessed token which guesses are *close*.
 */
export class InvalidResetTokenError extends Error {}

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

/**
 * Creates an account.
 *
 * ── It does not sign anyone in, and that is the backend's shape, not a choice here ────
 *
 * `AuthController.register` answers 201 with a `UserResponse` — no token pair. So the portal
 * cannot establish a session from a registration, and the signup action deliberately does not
 * try to by replaying the password into `login`: that would turn one user action into two
 * credential submissions, and would have to hold the password across them.
 *
 * `fullName` is optional in `RegisterRequest` (`@Size(max = 150)` and nothing more), so it is
 * omitted from the body rather than sent empty when the user leaves it blank.
 *
 * @throws {EmailAlreadyRegisteredError} 409 — the address already has an account
 * @throws {RegistrationRejectedError}   400 — the platform refused the payload
 * @throws {IdentityUnavailableError}    unreachable, 5xx, or an answer we cannot describe
 */
export async function register(
  email: string,
  password: string,
  fullName: string | undefined,
): Promise<void> {
  const response = await post('/api/v1/auth/register', {
    email,
    password,
    ...(fullName !== undefined && fullName.length > 0 ? { fullName } : {}),
  });

  if (response.status === 409) {
    throw new EmailAlreadyRegisteredError('An account with that email already exists.');
  }
  if (response.status === 400) {
    // The platform's own field messages are not forwarded: they name Java bean-validation
    // constraints, and the form already states every rule in language a person can act on.
    throw new RegistrationRejectedError('Those details were not accepted.');
  }
  if (response.status !== 201 && (response.status < 200 || response.status >= 300)) {
    throw new IdentityUnavailableError(`Unexpected ${response.status} from register.`);
  }
}

/**
 * Asks identity-service to email a reset link. **Always succeeds.**
 *
 * `AuthController.requestPasswordReset` answers 202 whether or not the address has an account —
 * `PasswordResetService.requestReset` does its work inside an `ifPresent` and returns normally
 * either way. That is the backend refusing to be an enumeration oracle, and this function
 * preserves it by having no failure the caller could branch on: a 202 and a 404 and a 400 all
 * return normally, so no code path above can accidentally reintroduce the distinction.
 *
 * @throws {IdentityUnavailableError} only when the platform could not be reached at all — which
 *         is not an answer about the address, so it does not leak one.
 */
export async function requestPasswordReset(email: string): Promise<void> {
  // `post` already throws `IdentityUnavailableError` on a network failure or a 5xx. Every other
  // status is deliberately ignored: there is nothing about it the caller may act on.
  await post('/api/v1/auth/password-reset/request', { email });
}

/**
 * Sets a new password from a reset token.
 *
 * The backend does three things here that the portal must not undo: the token is single-use
 * (`reset.consume()`), it is stored only as a SHA-256 hash, and a successful reset **revokes
 * every refresh token the account has**. The last is why this deliberately does not try to
 * establish a session afterwards — the user has just invalidated all of them, and the correct
 * next step is a fresh sign-in with the password they now know.
 *
 * @throws {InvalidResetTokenError}   401 — unknown, expired, or already used
 * @throws {RegistrationRejectedError} 400 — the new password failed the platform's own bounds
 * @throws {IdentityUnavailableError}  unreachable, 5xx, or an answer we cannot describe
 */
export async function confirmPasswordReset(token: string, newPassword: string): Promise<void> {
  const response = await post('/api/v1/auth/password-reset/confirm', { token, newPassword });

  if (response.status === 401) {
    throw new InvalidResetTokenError('That reset link is no longer valid.');
  }
  if (response.status === 400) {
    throw new RegistrationRejectedError('That password was not accepted.');
  }
  if (response.status < 200 || response.status >= 300) {
    throw new IdentityUnavailableError(`Unexpected ${response.status} from password reset.`);
  }
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
