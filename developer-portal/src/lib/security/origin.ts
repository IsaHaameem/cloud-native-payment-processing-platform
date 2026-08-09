import 'server-only';

import { env } from '@/lib/env';

/**
 * The first CSRF defence: a state-changing request must have come from this portal (M23.2).
 *
 * ── Why this exists when the cookie is already `SameSite=Strict` ──────────────────────
 *
 * Because `SameSite` is a browser promise, and this is the server's own check. It costs one
 * string comparison and it holds when the promise does not: an older browser, a `SameSite`
 * attribute lost to a proxy rewriting `Set-Cookie`, or a same-site attacker — a subdomain, which
 * `SameSite` considers "same site" and this check does not.
 *
 * ── `Origin`, then `Sec-Fetch-Site`, then refuse ──────────────────────────────────────
 *
 * `Origin` is sent on every state-changing browser request and is not settable by page script,
 * which makes it the right primary signal. `Sec-Fetch-Site` is the fallback for the cases
 * `Origin` is legitimately absent.
 *
 * A request with **neither** header is refused. That is deliberate and it is the decision worth
 * defending: those requests are not browsers, and a portal whose logout endpoint answers `curl`
 * is a portal whose logout endpoint answers anything on the network that can reach it. Nothing
 * in this application makes a state-changing request from a non-browser, so nothing legitimate
 * is refused. If that ever stops being true, the caller gets a credential, not an exemption.
 *
 * ── What M23.2b changed, and why it is not a relaxation ───────────────────────────────
 *
 * This compared the `Origin` header against a single configured string. That is correct for a
 * deployment and wrong for every other way the portal is actually reached: the same server
 * answers on `localhost`, on `127.0.0.1`, and on the machine's LAN address, and only one of
 * those spellings can be in `PORTAL_PUBLIC_ORIGIN`. Reaching it by any of the others produced a
 * refusal — reported, because both failures shared one message, as *"this form expired"* on a
 * form rendered one second earlier. That is the defect this milestone was opened for, and it
 * cost a bug report to find because the message described a different problem.
 *
 * The check is now: the canonical origin, plus any explicitly configured additional origins,
 * plus — **only when this portal is configured for a loopback address in the first place** — the
 * sibling spellings of that same machine. Every one of those is narrow:
 *
 * - The additional list is opt-in and empty by default, so no deployment gains an origin it did
 *   not name.
 * - The loopback allowance is gated on `PORTAL_PUBLIC_ORIGIN` *itself* being a loopback address.
 *   A deployment configured for `https://portal.example.com` never enters that branch, so it
 *   cannot be the thing that admits an attacker to a real site.
 *
 *   Gating on `NODE_ENV` was the first instinct and is subtly wrong: `docker compose up` runs the
 *   production build on `http://localhost:3000`, which is the same local-access situation and
 *   would have kept the original defect. What matters is not how the bundle was built but whether
 *   the portal is configured for a machine or for the internet.
 * - Even then it requires `Origin` to equal the request's own `Host`, to be a loopback name, *and*
 *   to be plain `http:`. A page on `https://evil.test` sends `Origin: https://evil.test` with
 *   `Host: localhost:3000`; those differ, so it is refused exactly as before. The only requests
 *   this admits are ones that were genuinely same-origin already.
 *
 * ── The two failures are told apart now ───────────────────────────────────────────────
 *
 * A stale synchronizer token and a cross-origin request are different events with different
 * fixes, and collapsing them into one message is what made a configuration mistake look like a
 * framework bug. They still both refuse the request; they no longer both say the same thing, and
 * a refusal here is logged with what was expected against what arrived.
 */

export class CrossOriginRequestError extends Error {
  constructor() {
    super('This request did not come from the portal.');
  }
}

/** Hostnames that mean "this machine". `new URL()` keeps IPv6 literals in brackets. */
const LOOPBACK_HOSTNAMES = new Set(['localhost', '127.0.0.1', '[::1]']);

/**
 * @throws {CrossOriginRequestError} if the request cannot be shown to have come from here.
 */
export function assertSameOrigin(headers: Headers): void {
  const origin = headers.get('origin');
  if (origin !== null) {
    if (isAllowedOrigin(origin, headers.get('host'))) return;
    reportRefusal(origin);
    throw new CrossOriginRequestError();
  }

  // `same-origin` covers a form post from our own page; `none` covers a user typing the URL or
  // opening a bookmark, which is a top-level navigation the browser attributes to no site.
  // `cross-site` and `same-site` are both refused — the second because a sibling subdomain is
  // exactly the attacker `SameSite=Strict` does not stop.
  const fetchSite = headers.get('sec-fetch-site');
  if (fetchSite === 'same-origin' || fetchSite === 'none') return;

  throw new CrossOriginRequestError();
}

function isAllowedOrigin(origin: string, host: string | null): boolean {
  if (origin === env.publicOrigin) return true;
  if (env.additionalOrigins.includes(origin)) return true;
  return isSiblingLoopbackSpelling(origin, host);
}

function hostnameOf(value: string): string | undefined {
  try {
    return new URL(value).hostname;
  } catch {
    return undefined;
  }
}

/**
 * @returns whether this portal is configured for local access, and the origin is a plain-http
 *          loopback address identical to the host the request was actually sent to.
 *
 * Every condition earns its place.
 *
 * The configured origin must itself be loopback, so the allowance exists only where "the portal
 * and the browser are on one machine" is already true by configuration.
 *
 * Without the host comparison this would admit any page claiming a loopback origin — which a
 * browser will never send, but a non-browser would. With it, the only thing admitted is a request
 * whose `Origin` and `Host` already agree, which is what "same origin" means.
 *
 * The scheme is checked separately because `Host` does not carry one: `https://localhost:3000`
 * and `http://localhost:3000` share a `host` and are *different origins*. Without that line the
 * allowance would quietly treat them as the same, which is the one thing an origin check may
 * never do. This was caught by its own test rather than by review.
 */
function isSiblingLoopbackSpelling(origin: string, host: string | null): boolean {
  if (host === null) return false;

  const configured = hostnameOf(env.publicOrigin);
  if (configured === undefined || !LOOPBACK_HOSTNAMES.has(configured)) return false;

  let parsed: URL;
  try {
    parsed = new URL(origin);
  } catch {
    return false;
  }

  return (
    parsed.protocol === 'http:' && parsed.host === host && LOOPBACK_HOSTNAMES.has(parsed.hostname)
  );
}

/**
 * Logged, because the alternative is what happened: a developer reaching the portal on a host it
 * was not configured for, seeing a message about an expired form, and having nothing anywhere to
 * connect the two. The origin is not a secret and no credential is interpolated — this module is
 * on the path of requests that carry one, so that rule holds even where it is not strictly
 * needed.
 */
function reportRefusal(origin: string): void {
  const allowed = [env.publicOrigin, ...env.additionalOrigins].join(', ');
  console.warn(
    `[security] Refused a state-changing request from origin "${origin}". ` +
      `This portal accepts: ${allowed}. ` +
      'Set PORTAL_PUBLIC_ORIGIN to the address users actually reach it on, or list the ' +
      'others in PORTAL_ADDITIONAL_ORIGINS.',
  );
}

/** The non-throwing form, for a middleware that must answer with a status rather than an error. */
export function isSameOrigin(headers: Headers): boolean {
  try {
    assertSameOrigin(headers);
    return true;
  } catch {
    return false;
  }
}
