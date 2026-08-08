import { type NextRequest, NextResponse } from 'next/server';

import { CsrfError, assertCsrfToken, clearCsrfToken } from '@/lib/security/csrf';
import { CrossOriginRequestError, assertSameOrigin } from '@/lib/security/origin';
import { destroySession } from '@/lib/session/lifecycle';

/**
 * Sign-out (M23.2).
 *
 * ── A Route Handler rather than a Server Action ───────────────────────────────────────
 *
 * Sign-out has to work when nothing else does. A Server Action needs the framework's client
 * runtime to have loaded in order to be addressable; a plain `<form method="post" action="/logout">`
 * needs only a browser. For the one control whose job is "get me out of here", that is the right
 * dependency to not have.
 *
 * ── There is no `GET` export, and that is the security property ───────────────────────
 *
 * A `GET /logout` is forced-logout CSRF: any third-party page can fire it with `<img src>`. Next.js
 * answers 405 for a method with no export, so the absence of a `GET` here is the control — not an
 * omission. It is written down because "we just didn't add one" and "adding one is a
 * vulnerability" look identical in a diff.
 *
 * ── Failures still clear the browser ──────────────────────────────────────────────────
 *
 * A forged request is refused. A genuine one whose backend revocation fails still clears the
 * cookie and redirects, because a user who asked to leave must leave — the revocation result only
 * decides whether the refresh token dies now or expires in a week.
 */

export async function POST(request: NextRequest): Promise<NextResponse> {
  try {
    assertSameOrigin(request.headers);
    const formData = await request.formData();
    await assertCsrfToken(formData.get('csrfToken'));
  } catch (error) {
    if (error instanceof CsrfError || error instanceof CrossOriginRequestError) {
      // Refused, and deliberately quiet: the user did not ask for this, so there is nothing to
      // tell them. Home rather than an error page, which would be a message meant for the forger.
      return NextResponse.redirect(new URL('/', request.url), { status: 303 });
    }
    throw error;
  }

  await destroySession();
  await clearCsrfToken();

  // 303, not 302: the request was a POST and the result must be fetched with GET. Browsers do
  // this for 302 in practice, but only 303 actually says so.
  return NextResponse.redirect(new URL('/login', request.url), { status: 303 });
}
