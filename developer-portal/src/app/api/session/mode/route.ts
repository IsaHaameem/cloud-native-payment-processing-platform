import { type NextRequest, NextResponse } from 'next/server';

import { CsrfError, assertCsrfToken } from '@/lib/security/csrf';
import { CrossOriginRequestError, assertSameOrigin } from '@/lib/security/origin';
import { persistSession } from '@/lib/session/lifecycle';
import { readSessionCookie } from '@/lib/session/store';

/**
 * Switching the session between test and live data (M23.2).
 *
 * A Route Handler for the same reason `/logout` is one: the control it serves is a plain form, so
 * it works without the client runtime. `POST` only — there is no `GET` export, and a mode change
 * reachable by navigation would be a cross-site request away from moving a merchant onto live
 * data, which is the exact confusion D184 exists to prevent.
 *
 * The redirect goes back where the user was. `Referer` is used for that and is *validated as a
 * same-origin path* before use — an unvalidated `Referer` redirect is an open redirect with extra
 * steps.
 */

export async function POST(request: NextRequest): Promise<NextResponse> {
  try {
    assertSameOrigin(request.headers);
    const formData = await request.formData();
    await assertCsrfToken(formData.get('csrfToken'));

    const requested = String(formData.get('mode') ?? '');
    if (requested !== 'test' && requested !== 'live') {
      // A tampered or stale form. The session keeps whatever it had, which is the safe answer:
      // the alternative is guessing, and one of the two guesses is "live".
      return backToWhereTheyWere(request);
    }

    const session = await readSessionCookie();
    if (!session) {
      return NextResponse.redirect(new URL('/login', request.url), { status: 303 });
    }

    if (session.mode !== requested) {
      await persistSession({ ...session, mode: requested });
    }
    return backToWhereTheyWere(request);
  } catch (error) {
    if (error instanceof CsrfError || error instanceof CrossOriginRequestError) {
      return NextResponse.redirect(new URL('/', request.url), { status: 303 });
    }
    throw error;
  }
}

/**
 * @returns a redirect to the page the form was submitted from, or the app root.
 *
 * The `Referer` is reduced to its pathname *after* confirming it is this origin, so a forged or
 * foreign value can contribute a path but never a destination.
 */
function backToWhereTheyWere(request: NextRequest): NextResponse {
  const referer = request.headers.get('referer');
  let target = '/foundation';

  if (referer) {
    try {
      const url = new URL(referer);
      if (url.origin === new URL(request.url).origin) {
        target = url.pathname + url.search;
      }
    } catch {
      // A malformed Referer is simply ignored.
    }
  }

  return NextResponse.redirect(new URL(target, request.url), { status: 303 });
}
