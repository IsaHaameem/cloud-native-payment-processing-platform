import { type NextRequest, NextResponse } from 'next/server';

import {
  CSRF_COOKIE,
  csrfCookieOptions,
  isWellFormedCsrfToken,
  newCsrfToken,
} from '@/lib/security/csrf';
import { safeRedirectPath } from '@/lib/security/redirect';
import { IdentityUnavailableError, RejectedTokenError } from '@/lib/session/identity';
import { refreshSession } from '@/lib/session/refresh';
import {
  type Session,
  SESSION_COOKIE,
  accessTokenIsUsable,
  clearedSessionCookieOptions,
  decodeSession,
  encodeSession,
  sessionCookieOptions,
} from '@/lib/session/session';

/**
 * The portal's authentication gate and its **only** refresh point (M23.2, D198).
 *
 * ── Why refresh lives here and nowhere else ───────────────────────────────────────────
 *
 * Next.js lets a cookie be written only where a response is being constructed. A Server
 * Component cannot do it. That single constraint decides the whole design: if a refresh happened
 * during a render, identity-service would rotate the token — irreversibly, since rotation is
 * single-use — and the new one could not be stored. The next request would present a revoked
 * token and the user would be signed out by nothing worse than a page load.
 *
 * Middleware runs once per request, before the render, on a response it owns. So it refreshes
 * anything inside a two-minute skew and hands the render a token guaranteed good for the whole
 * of that request. This is the property the M23 architecture required and this file preserves:
 * refresh in one controlled server-side path, never once per parallel RSC fetch.
 *
 * ── Node runtime, not Edge ────────────────────────────────────────────────────────────
 *
 * The Edge runtime has its own module registry, so the refresh coordinator's mutex and replay
 * cache there would be a *different* mutex and a different cache from the server's — two
 * uncoordinated refreshers, which is precisely the failure this milestone exists to prevent.
 * One runtime, one module instance, one guarantee.
 *
 * ── What it deliberately does not do ──────────────────────────────────────────────────
 *
 * No API calls beyond the refresh, and that one only when the token is actually near expiry. No
 * merchant lookup, no user lookup. Middleware runs on every request including prefetches; work
 * placed here is work multiplied by the whole navigation graph.
 *
 * It is also not the security boundary. `requireSession` is. This is the redirect that stops a
 * protected page from *rendering* before it can redirect itself — which is a real requirement
 * (no flash of protected content) but is a UX guarantee resting on a matcher, not an
 * authorization check resting on a signature.
 */

export const config = {
  // Node.js so this shares one module registry — and therefore one refresh coordinator — with
  // the rest of the server. See the note above.
  runtime: 'nodejs',
  /*
   * Everything except Next's own internals and static assets.
   *
   * Written as an exclusion rather than a list of protected prefixes on purpose: a matcher
   * enumerating what to protect fails *open* the day someone adds a route and forgets it. This
   * one fails closed — a new route is covered until it is deliberately excluded — and the public
   * paths are then named explicitly in `PUBLIC_PATHS` below, where the reason for each is
   * visible.
   */
  matcher: ['/((?!_next/static|_next/image|favicon.ico|icon.svg|robots.txt).*)'],
};

/**
 * Paths reachable without a session.
 *
 * `/` is the marketing page, which renders differently for a signed-in visitor but must render.
 * `/login` and `/signup` obviously — they are the two halves of the entry flow, and a signup page
 * behind an authentication gate is a door that only opens from the inside. `/api/health` is the
 * container's liveness probe and must answer before anyone has ever signed in.
 */
const PUBLIC_PATHS = new Set(['/', '/login', '/signup', '/api/health']);

/**
 * Public paths a *signed-in* visitor is sent away from.
 *
 * Both are entry points to a session that already exists, so landing on either is a dead end:
 * `/login` would ask for credentials already held, and `/signup` would offer a second account to
 * someone who is signed into their first.
 */
const ENTRY_PATHS = new Set(['/login', '/signup']);

function isPublic(pathname: string): boolean {
  return PUBLIC_PATHS.has(pathname);
}

export async function middleware(request: NextRequest): Promise<NextResponse> {
  const { pathname, search } = request.nextUrl;

  /*
   * The CSRF token is minted here for the same reason the refresh happens here: a Server
   * Component cannot set a cookie, and the pages that render forms are Server Components. So the
   * middleware guarantees the cookie exists before any render reads it, and `readCsrfToken` is
   * read-only by construction rather than by convention.
   *
   * Set on the request as well as the response, so the render happening *now* sees the token
   * rather than the first visitor getting a form with an empty field.
   */
  const csrf = issuedCsrfToken(request);

  const sealed = request.cookies.get(SESSION_COOKIE)?.value;
  const session = await decodeSession(sealed);

  if (!session) {
    if (isPublic(pathname)) return withCsrf(NextResponse.next({ request }), csrf);
    return withCsrf(redirectToLogin(request, pathname + search, sealed !== undefined), csrf);
  }

  // Signed in and standing on an entry page: send them where they were going. Without this,
  // a bookmarked `/login` is a dead end for someone who is already authenticated.
  if (ENTRY_PATHS.has(pathname)) {
    const next = safeRedirectPath(request.nextUrl.searchParams.get('next'));
    return withCsrf(NextResponse.redirect(new URL(next, request.url)), csrf);
  }

  if (accessTokenIsUsable(session)) {
    return withCsrf(NextResponse.next({ request }), csrf);
  }

  return withCsrf(await refreshAndContinue(request, session, pathname, search), csrf);
}

/**
 * @returns a freshly minted token if the request carried none, otherwise `undefined` — meaning
 *          there is nothing to write back.
 *
 * A token already present is kept. Minting one per request would invalidate the form in every
 * other tab on every navigation.
 */
function issuedCsrfToken(request: NextRequest): string | undefined {
  const existing = request.cookies.get(CSRF_COOKIE)?.value;
  if (isWellFormedCsrfToken(existing)) return undefined;

  const token = newCsrfToken();
  request.cookies.set(CSRF_COOKIE, token);
  return token;
}

function withCsrf(response: NextResponse, token: string | undefined): NextResponse {
  if (token !== undefined) response.cookies.set(CSRF_COOKIE, token, csrfCookieOptions());
  return response;
}

async function refreshAndContinue(
  request: NextRequest,
  session: Session,
  pathname: string,
  search: string,
): Promise<NextResponse> {
  let refreshed: Session;
  try {
    refreshed = await refreshSession(session);
  } catch (error) {
    if (error instanceof RejectedTokenError) {
      // The refresh token is genuinely dead — revoked by a logout elsewhere, or already rotated
      // by a process this one cannot see. The session is over; clear it rather than leaving a
      // cookie that will fail identically on every subsequent request.
      if (isPublic(pathname)) return clearedResponse(NextResponse.next({ request }));
      return clearedResponse(redirectToLogin(request, pathname + search, true));
    }
    if (error instanceof IdentityUnavailableError) {
      /*
       * The platform is unwell. This is emphatically **not** a reason to sign anyone out — that
       * would turn a thirty-second backend blip into every session in the portal ending at once,
       * which is worse than the blip.
       *
       * The request proceeds on the existing access token. It may still be valid: the skew that
       * brought us here is two minutes wide, so there is usually real life left in it. If there
       * is not, the gateway answers 401, the transport raises `AuthenticationError`, and the user
       * sees an error rather than a silent logout — the honest outcome.
       */
      return NextResponse.next({ request });
    }
    throw error;
  }

  const cookie = await encodeSession(refreshed);

  /*
   * Written twice, and both are necessary.
   *
   * `request.cookies.set` before forwarding makes the rotated session visible to *this* render,
   * so the page about to be built reads the fresh access token rather than the expired one it
   * arrived with. `response.cookies.set` is what actually reaches the browser. Setting only the
   * response would render this request with a stale token; setting only the request would rotate
   * the token and then throw the new one away — the exact failure the whole design avoids.
   */
  request.cookies.set(SESSION_COOKIE, cookie);
  const response = NextResponse.next({ request });
  response.cookies.set(SESSION_COOKIE, cookie, sessionCookieOptions());
  return response;
}

function redirectToLogin(
  request: NextRequest,
  returnTo: string,
  clearStaleCookie: boolean,
): NextResponse {
  const url = new URL('/login', request.url);
  const next = safeRedirectPath(returnTo, '');
  if (next) url.searchParams.set('next', next);

  const response = NextResponse.redirect(url);
  // A cookie that decoded to nothing — tampered, superseded, expired, or sealed under a rotated
  // secret — is cleared on the way out. Leaving it would make every subsequent request repeat
  // the same failed decode, and would leave the user looking at a browser that still appears to
  // hold a session.
  return clearStaleCookie ? clearedResponse(response) : response;
}

function clearedResponse(response: NextResponse): NextResponse {
  response.cookies.set(SESSION_COOKIE, '', clearedSessionCookieOptions());
  return response;
}
