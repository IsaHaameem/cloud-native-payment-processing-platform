import 'server-only';

import { env } from '@/lib/env';

/**
 * The first CSRF defence: a state-changing request must have come from this origin (M23.2).
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
 */

export class CrossOriginRequestError extends Error {
  constructor() {
    super('This request did not come from the portal.');
  }
}

/**
 * @throws {CrossOriginRequestError} if the request cannot be shown to have come from here.
 */
export function assertSameOrigin(headers: Headers): void {
  const origin = headers.get('origin');
  if (origin !== null) {
    if (origin !== env.publicOrigin) throw new CrossOriginRequestError();
    return;
  }

  // `same-origin` covers a form post from our own page; `none` covers a user typing the URL or
  // opening a bookmark, which is a top-level navigation the browser attributes to no site.
  // `cross-site` and `same-site` are both refused — the second because a sibling subdomain is
  // exactly the attacker `SameSite=Strict` does not stop.
  const fetchSite = headers.get('sec-fetch-site');
  if (fetchSite === 'same-origin' || fetchSite === 'none') return;

  throw new CrossOriginRequestError();
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
