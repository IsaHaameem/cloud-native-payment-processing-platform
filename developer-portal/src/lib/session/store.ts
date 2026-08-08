import 'server-only';

import { cookies } from 'next/headers';

import {
  type Session,
  SESSION_COOKIE,
  clearedSessionCookieOptions,
  decodeSession,
  encodeSession,
  sessionCookieOptions,
} from './session';

/**
 * Reading and writing the session cookie from a request context (M23.2).
 *
 * Split from `session.ts` — which is pure — so that the encode/decode logic can be tested
 * without a Next.js request scope, and so `middleware.ts`, which has its own cookie API and
 * cannot use `next/headers`, shares the pure half rather than reimplementing it.
 *
 * ── `writeSession` may legitimately fail, and that is designed for ────────────────────
 *
 * Next.js allows `cookies().set` only where a response is being built: Route Handlers, Server
 * Actions, and middleware. In a Server Component it throws. That is not a bug to work around but
 * the constraint the whole refresh design is built on — see `refresh.ts`, whose replay cache
 * exists precisely so a rotation that could not be persisted here is recoverable by the next
 * request instead of signing the user out.
 *
 * So this returns a boolean rather than throwing. A caller that cannot persist has not failed;
 * it has learned that persistence is someone else's turn.
 */

export async function readSessionCookie(): Promise<Session | null> {
  const store = await cookies();
  return decodeSession(store.get(SESSION_COOKIE)?.value);
}

/** @returns whether the cookie was actually written. See the note above. */
export async function writeSession(session: Session): Promise<boolean> {
  try {
    const store = await cookies();
    store.set(SESSION_COOKIE, await encodeSession(session), sessionCookieOptions());
    return true;
  } catch {
    return false;
  }
}

export async function clearSession(): Promise<boolean> {
  try {
    const store = await cookies();
    store.set(SESSION_COOKIE, '', clearedSessionCookieOptions());
    return true;
  } catch {
    return false;
  }
}
