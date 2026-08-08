import 'server-only';

import { redirect } from 'next/navigation';

import { type Session } from './session';
import { readSessionCookie } from './store';

/**
 * Route protection, as the guards every server-side data path calls (M23.1 shape, M23.2 body).
 *
 * ── The shape, and why it is this shape ───────────────────────────────────────────────
 *
 * `middleware.ts` redirects an unauthenticated request before it renders, but that is a
 * *response-time* control, not the security boundary: it is matched by a path pattern, and a
 * route the matcher fails to cover is a route with no protection at all. These functions are the
 * real check, and they are the only way to obtain a credential — so a Server Component that
 * forgets to call one cannot accidentally fetch merchant data, because it has nothing to fetch
 * it with.
 *
 * That is the property worth preserving as the portal grows: authorization is not a step a page
 * may omit, it is where the token comes from.
 *
 * ── Why these do not refresh ──────────────────────────────────────────────────────────
 *
 * A guard runs inside a render, where a rotated refresh token cannot be written to a cookie. The
 * middleware has already guaranteed the access token is good for the whole request — it refreshes
 * anything inside a two-minute skew — so a guard that also refreshed would be duplicating the one
 * controlled path for no benefit and creating exactly the per-RSC-fetch refresh storm the M23
 * architecture rules out.
 */

export type { Session } from './session';

/** A session whose user has completed onboarding, so `merchantId` is known to be present. */
export type MerchantSession = Session & { readonly merchantId: string };

/**
 * Reads and decrypts the session cookie.
 *
 * Returns `null` rather than throwing on purpose: "is anyone signed in" is a question the
 * marketing page and the login page both ask without wanting a redirect.
 */
export async function readSession(): Promise<Session | null> {
  return readSessionCookie();
}

/** @returns the current session, or redirects to sign in, preserving where the user was going. */
export async function requireSession(returnTo?: string): Promise<Session> {
  const session = await readSession();
  if (!session) {
    redirect(returnTo ? `/login?next=${encodeURIComponent(returnTo)}` : '/login');
  }
  return session;
}

/**
 * @returns a session that is known to have a merchant, or redirects to onboarding.
 *
 * Separate from {@link requireSession} because "signed in" and "has somewhere to act" are
 * genuinely different states, and the platform tells them apart too: M23.0's gateway answers a
 * merchant-less session with 403, not 401. Conflating them would send a brand-new user who has
 * just verified their email back to a login page they are already past.
 */
export async function requireMerchant(returnTo?: string): Promise<MerchantSession> {
  const session = await requireSession(returnTo);
  if (session.merchantId === undefined) {
    redirect('/onboarding');
  }
  return session as MerchantSession;
}

/**
 * @returns a session holding `role`, or redirects to the dashboard.
 *
 * Used by M24's admin route group, whose layout calls it so an admin bundle is never routed for
 * a non-admin. Redirecting rather than rendering a 403 page is the deliberate choice: a route
 * that answers differently for an admin and a non-admin tells a non-admin the route exists.
 */
export async function requireRole(role: string, returnTo?: string): Promise<Session> {
  const session = await requireSession(returnTo);
  if (!session.roles.includes(role)) {
    redirect('/foundation');
  }
  return session;
}
