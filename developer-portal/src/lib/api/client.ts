import 'server-only';

import { AuthenticationError } from '@/lib/api/errors';
import { type CallOptions, type OperationId, call } from '@/lib/api/transport';
import { IdentityUnavailableError, RejectedTokenError } from '@/lib/session/identity';
import { refreshSession } from '@/lib/session/refresh';
import { type Session } from '@/lib/session/session';
import { writeSession } from '@/lib/session/store';

/**
 * The one authenticated path to the platform (M23.2, D188).
 *
 * `transport.ts` knows how to call an operation; it does not know what a session is. This module
 * is the join: it takes a `Session`, hands the transport the credentials it needs, and owns the
 * one recovery the portal performs — a single retry after a coordinated refresh.
 *
 * Everything above it — pages, Server Actions, Route Handlers — calls this and nothing else.
 * There is deliberately no other function in the portal that puts an access token on a request.
 *
 * ── The retry, and why it is a backstop rather than the mechanism ─────────────────────
 *
 * `middleware.ts` refreshes proactively, so by the time a page renders its access token is good
 * for at least two more minutes. A 401 here therefore means something the middleware could not
 * foresee: the token was revoked mid-request, identity-service was unreachable when the
 * middleware tried, or a clock moved. Rare, and worth one retry rather than an error page.
 *
 * The retry is still safe for mutations, which is the part worth being explicit about: a 401 is
 * refused *at the gateway*, before any downstream service sees the request, so replaying it
 * cannot double-charge. That is a different situation from the transport's own retry, which is
 * restricted to safe methods precisely because a connection failure gives no such guarantee.
 *
 * ── Persistence is best-effort, and that is not a shortcut ────────────────────────────
 *
 * If this runs inside a Server Component, `writeSession` cannot set a cookie and returns `false`.
 * The rotated token is not lost: `refresh.ts` holds it in the replay cache keyed by the *old*
 * token, so the next request — arriving at middleware, which can write cookies — finds it and
 * persists it. Ignoring the failed write here is the design working, not an omission.
 */

export interface AuthenticatedCall extends Omit<CallOptions, 'credentials'> {
  readonly session: Session;
}

/**
 * Calls one published operation on behalf of a session.
 *
 * @throws {AuthenticationError} the session is over; the caller should redirect to `/login`.
 * @throws {PlatformError} for every other non-2xx answer, classified by type.
 */
export async function callAs<T>(
  operationId: OperationId,
  { session, ...options }: AuthenticatedCall,
): Promise<T> {
  try {
    return await call<T>(operationId, {
      ...options,
      credentials: { accessToken: session.accessToken, mode: session.mode },
    });
  } catch (error) {
    if (!(error instanceof AuthenticationError)) throw error;
    return retryAfterRefresh<T>(operationId, options, session, error);
  }
}

async function retryAfterRefresh<T>(
  operationId: OperationId,
  options: Omit<CallOptions, 'credentials'>,
  session: Session,
  original: AuthenticationError,
): Promise<T> {
  let refreshed: Session;
  try {
    refreshed = await refreshSession(session);
  } catch (error) {
    if (error instanceof RejectedTokenError) {
      // The refresh token is dead too. There is no credential left to try; the session is over.
      throw original;
    }
    if (error instanceof IdentityUnavailableError) {
      // Cannot refresh because the platform is unwell — which is not the user's session ending.
      // The original 401 is rethrown rather than the unavailability, because the caller's
      // question is "did this call work", and the honest answer is the answer the gateway gave.
      throw original;
    }
    throw error;
  }

  // Best-effort. See the note at the top of the file.
  await writeSession(refreshed);

  return call<T>(operationId, {
    ...options,
    credentials: { accessToken: refreshed.accessToken, mode: refreshed.mode },
  });
}
