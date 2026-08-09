/**
 * Open-redirect prevention (M23.2).
 *
 * The login flow carries a `?next=` so that a user bounced off a deep link lands back on it. That
 * parameter is attacker-controlled, and a login page that redirects wherever it is told is a
 * phishing primitive with this platform's domain on it: the victim signs in at the real portal
 * and is handed to a page that looks like the next step.
 *
 * ── An allowlist by shape, not a blocklist ────────────────────────────────────────────
 *
 * Only same-origin *paths* are accepted, and the check is what a path must look like rather than
 * what an attack looks like. Every one of these is rejected:
 *
 * - `https://evil.test/x` — absolute, and the obvious case.
 * - `//evil.test/x` — protocol-relative. Browsers treat it as absolute; a naive
 *   `startsWith('/')` does not, which is why that check alone is the classic mistake.
 * - a path containing a backslash, which several browsers normalise to `/` *after* a server-side
 *   check has already approved the string.
 * - anything containing a control character — a header-splitting attempt, or corruption.
 * - `/login`, `/logout`, `/signup` — not an attack, but a redirect target that bounces the user
 *   straight back out of the session they just established.
 *
 * Deliberately **not** parsed with `new URL(next, base)` and then origin-compared. That works,
 * and it makes the portal's safety depend on WHATWG URL normalisation agreeing with every
 * browser's — a bet this does not need to take when "starts with one slash, no backslash, no
 * second slash" is the entire rule.
 *
 * This module has no `server-only` marker on purpose: it is pure string logic with no secrets,
 * and the login form needs to build a link with it.
 */

/**
 * Where a user goes when they have no valid destination of their own.
 *
 * `/dashboard` — the authenticated entry point §6.1 fixes, and the route the merchant guard
 * protects. It was `/foundation` while the portal had no dashboard; sending a signed-in merchant
 * to the design-system page was always a placeholder, and `requireMerchant` now routes a user
 * with no merchant on to `/onboarding` from here rather than the other way round.
 */
export const DEFAULT_AFTER_LOGIN = '/dashboard';

/** Paths that must never be a post-login destination. */
const NEVER_REDIRECT_TO = ['/login', '/logout', '/signup'];

const BACKSLASH = 0x5c;
const DELETE = 0x7f;
const FIRST_PRINTABLE = 0x20;

/**
 * Character-by-character rather than a regular expression, because the two things that matter
 * here — a literal backslash and the C0 control range — are exactly what gets mangled passing
 * through escaping layers, and a silently broken character class in *this* function is an open
 * redirect that still looks defended.
 */
function containsBackslashOrControl(value: string): boolean {
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    if (code === BACKSLASH || code < FIRST_PRINTABLE || code === DELETE) return true;
  }
  return false;
}

/**
 * @returns `next` if it is a safe same-origin path, otherwise {@link DEFAULT_AFTER_LOGIN}.
 */
export function safeRedirectPath(
  next: string | null | undefined,
  fallback: string = DEFAULT_AFTER_LOGIN,
): string {
  if (typeof next !== 'string' || next.length === 0) return fallback;

  // One leading slash, and no second one — which is what separates a path from a
  // protocol-relative URL.
  if (!next.startsWith('/') || next.startsWith('//')) return fallback;

  if (containsBackslashOrControl(next)) return fallback;

  const path = next.split(/[?#]/)[0] ?? '';
  if (NEVER_REDIRECT_TO.includes(path)) return fallback;

  return next;
}
