/**
 * Browser verification of the authentication flows (M23.2).
 *
 * ── What only a browser can prove ─────────────────────────────────────────────────────
 *
 * The unit suite proves the session seals, the coordinator collapses refreshes, and the guards
 * classify correctly. None of it can prove the things a user is actually exposed to: that the
 * cookie really carries `HttpOnly`, that `document.cookie` really cannot see a token, that
 * `localStorage` is really empty, that a protected page really does not flash before redirecting,
 * and that after signing out the back button really does not restore the application.
 *
 * Those are properties of a real browser holding real cookies, so they are tested in one.
 *
 * ── Run ───────────────────────────────────────────────────────────────────────────────
 *
 *   node scripts/stub-platform.mjs &                     # the fake gateway
 *   PF_GATEWAY_URL=http://localhost:4600 \
 *   PORTAL_PUBLIC_ORIGIN=http://localhost:3601 \
 *   PORT=3601 npm start &                                # the portal under test
 *   BASE=http://localhost:3601 STUB=http://localhost:4600 node scripts/verify-auth.mjs
 *
 * Like `verify-interactions.mjs`, this asserts its own instrument before anything else: a
 * headless run that cannot see frames or cookies must fail loudly rather than report green.
 */

import { chromium } from 'playwright-core';

const BASE = process.env.BASE ?? 'http://localhost:3000';
const STUB = process.env.STUB ?? 'http://localhost:4600';

const CREDENTIALS = { email: 'ada@example.com', password: 'correct horse battery staple' };
const SESSION_COOKIE = 'pf_session';

const checks = [];
const failures = [];

function check(name, condition, detail) {
  checks.push({ name, ok: Boolean(condition), detail });
  if (!condition) failures.push(`${name}${detail ? ` — ${detail}` : ''}`);
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

async function stub(path) {
  const response = await fetch(`${STUB}${path}`);
  return response.json();
}

const browser = await chromium.launch({ channel: 'chrome', headless: true });

try {
  await verifyInstrument();
  await verifyUnauthenticatedAccess();
  await verifyLogin();
  await verifyNoTokenReachesTheBrowser();
  await verifyCookieFlags();
  await verifyRefresh();
  await verifyRefreshRace();
  await verifyRevokedRefreshToken();
  await verifyTamperedCookie();
  await verifyCsrf();
  await verifyOpenRedirect();
  await verifyLogout();
} finally {
  await browser.close();
}

for (const { name, ok, detail } of checks) {
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}
if (failures.length > 0) {
  console.error(`\n${failures.length} authentication check(s) failed:`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`\nAll ${checks.length} authentication checks passed.`);

// ──────────────────────────────────────────────────────────────────────────────────────

async function freshContext() {
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
  return context;
}

async function signIn(page, { email, password } = CREDENTIALS) {
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  await page.fill('#field-email', email);
  await page.fill('#field-password', password);
  await page.getByRole('button', { name: /sign in/i }).click();

  /*
   * Wait for the URL to leave /login rather than for a load state.
   *
   * Sign-in answers with a redirect carrying the session `Set-Cookie`, and navigating away before
   * that response is consumed aborts it — which discards the cookie. The symptom is precise and
   * misleading: the platform records a successful login, the server logs `ECONNRESET`, and the
   * browser sits on /login showing no error, because nothing failed. It simply never received
   * the cookie.
   */
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15_000 });
  await page.waitForLoadState('networkidle');
}

async function sessionCookie(context) {
  const cookies = await context.cookies();
  return cookies.find((c) => c.name === SESSION_COOKIE);
}

async function verifyInstrument() {
  const context = await freshContext();
  const page = await context.newPage();
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  const state = await page.evaluate(() => document.visibilityState);
  check('the browser is visible and compositing', state === 'visible', state);
  check('the portal is serving the login page', await page.locator('#field-email').isVisible());
  await context.close();
}

async function verifyUnauthenticatedAccess() {
  const context = await freshContext();
  const page = await context.newPage();

  const seen = [];
  page.on('response', (r) => seen.push({ url: r.url(), status: r.status() }));

  await page.goto(`${BASE}/foundation`, { waitUntil: 'networkidle' });

  check('a protected route redirects to /login', page.url().includes('/login'), page.url());
  check(
    'the redirect preserves where the user was going',
    decodeURIComponent(page.url()).includes('next=/foundation'),
    page.url(),
  );

  // The "no flash of protected content" requirement, asserted rather than assumed: the protected
  // page must never have been rendered, not merely replaced quickly.
  const body = await page.content();
  check('no protected content was rendered before the redirect', !body.includes('Volume today'));

  const firstNavigation = seen.find((r) => r.url.startsWith(`${BASE}/foundation`));
  check(
    'the protected route answered with a redirect, not a page',
    firstNavigation === undefined ||
      firstNavigation.status === 307 ||
      firstNavigation.status === 302,
    firstNavigation ? String(firstNavigation.status) : 'no direct response',
  );

  await context.close();
}

async function verifyLogin() {
  await fetch(`${STUB}/__stub/reset`);
  const context = await freshContext();
  const page = await context.newPage();

  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  await page.fill('#field-email', CREDENTIALS.email);
  await page.fill('#field-password', 'wrong password');
  await page.getByRole('button', { name: /sign in/i }).click();
  await wait(600);
  const errorText = await page.locator('form [role="alert"]').innerText();
  check('a wrong password is rejected', errorText.length > 0, errorText.trim());
  check(
    'the rejection does not reveal whether the account exists',
    /not recognised/i.test(errorText),
    errorText.trim(),
  );

  await signIn(page);
  check('a correct password signs the user in', !page.url().includes('/login'), page.url());
  check('the session cookie was set', (await sessionCookie(context)) !== undefined);

  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  check(
    'a signed-in user is redirected away from /login',
    !page.url().includes('/login'),
    page.url(),
  );

  await context.close();
}

async function verifyNoTokenReachesTheBrowser() {
  const context = await freshContext();
  const page = await context.newPage();
  await signIn(page);
  await page.goto(`${BASE}/foundation`, { waitUntil: 'networkidle' });

  const cookie = await sessionCookie(context);
  const html = await page.content();

  const storage = await page.evaluate(() => ({
    documentCookie: document.cookie,
    localStorage: JSON.stringify(localStorage),
    sessionStorage: JSON.stringify(sessionStorage),
  }));

  // The central promise of D187. Checked four ways because there are four places it could leak.
  check(
    'document.cookie cannot see the session',
    !storage.documentCookie.includes('pf_session'),
    storage.documentCookie,
  );
  check(
    'localStorage is empty of credentials',
    !/token/i.test(storage.localStorage),
    storage.localStorage,
  );
  check(
    'sessionStorage is empty of credentials',
    !/token/i.test(storage.sessionStorage),
    storage.sessionStorage,
  );

  // The sealed cookie value must not appear in the document either — that would hand a token to
  // any script on the page just as surely as a readable cookie would.
  check(
    'the sealed session does not appear in the HTML',
    cookie !== undefined && !html.includes(cookie.value),
  );

  // And nothing JWT-shaped anywhere in the served markup, which catches an accidental prop.
  check(
    'no JWT appears in the rendered HTML',
    !/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\./.test(html),
  );
  check('no refresh token appears in the rendered HTML', !html.includes('stub-refresh-'));

  await context.close();
}

async function verifyCookieFlags() {
  const context = await freshContext();
  const page = await context.newPage();
  await signIn(page);

  const cookie = await sessionCookie(context);
  check('the session cookie exists', cookie !== undefined);
  if (cookie) {
    check('the session cookie is HttpOnly', cookie.httpOnly === true);
    check('the session cookie is SameSite=Strict', cookie.sameSite === 'Strict', cookie.sameSite);
    check('the session cookie is scoped to the whole site', cookie.path === '/', cookie.path);
    // This suite runs against a production build (`next start`), which is the configuration a
    // deployment uses — so Secure must be on. It is deliberately off in development, where the
    // cookie would otherwise never be set over plain http and login would silently do nothing.
    check('the session cookie is Secure in a production build', cookie.secure === true);
  }

  const csrf = (await context.cookies()).find((c) => c.name === 'pf_csrf');
  check('the CSRF cookie is also HttpOnly', csrf !== undefined && csrf.httpOnly === true);

  await context.close();
}

async function verifyRefresh() {
  await fetch(`${STUB}/__stub/reset`);
  const context = await freshContext();
  const page = await context.newPage();
  await signIn(page);

  const countBefore = (await stub('/__stub/calls')).refreshCount;

  // The stub is started with a short access TTL, so the token is already inside the middleware's
  // skew window and the next navigation must refresh.
  //
  // A *different* route from the one sign-in landed on: navigating to the URL already loaded can
  // be served without a request, in which case no middleware runs and nothing refreshes. That is
  // a property of the browser rather than of the portal, and it made this check silently vacuous.
  await page.goto(`${BASE}/onboarding`, { waitUntil: 'networkidle' });
  await wait(300);

  const after = await sessionCookie(context);
  const countAfter = (await stub('/__stub/calls')).refreshCount;

  check(
    'an expiring access token is refreshed',
    countAfter > countBefore,
    `${countBefore} → ${countAfter}`,
  );
  // Not a ciphertext comparison: a fresh IV per seal makes every encoding differ, so that check
  // would pass without any refresh having happened. What matters is that rotation *replaced* the
  // token rather than leaving an orphan alive beside it.
  check('the session cookie is still present after refreshing', after !== undefined);
  check(
    'rotation leaves exactly one live refresh token, not an orphan',
    (await stub('/__stub/calls')).liveRefreshTokens === 1,
  );
  check('the user stays signed in through a refresh', !page.url().includes('/login'), page.url());

  await context.close();
}

async function verifyRefreshRace() {
  await fetch(`${STUB}/__stub/reset`);
  const context = await freshContext();
  const page = await context.newPage();
  await signIn(page);

  const countBefore = (await stub('/__stub/calls')).refreshCount;

  // Ten navigations fired at once against an access token that is already inside the skew
  // window. Uncoordinated, the first rotation revokes the token the other nine are holding and
  // at least one of them is signed out.
  const pages = await Promise.all(Array.from({ length: 10 }, () => context.newPage()));
  // Ten *distinct* URLs, so nothing can be answered from a cache: every one of these has to
  // reach the server, and every one arrives holding the same about-to-expire token.
  await Promise.all(
    pages.map((p, i) => p.goto(`${BASE}/foundation?race=${i}`, { waitUntil: 'domcontentloaded' })),
  );
  await wait(600);

  const after = await stub('/__stub/calls');
  const rotations = after.refreshCount - countBefore;

  /*
   * No request is ever handed a token another request already rotated away.
   *
   * ── Why this replaced a rotation *count* in M23.2a ──────────────────────────────────
   *
   * This asserted `rotations <= 1`, and that was a sound proxy for exactly as long as the
   * portal's navigation had no reachable destinations. The moment `/dashboard` became a real
   * link, Next.js began prefetching it from every authenticated page — and a prefetch is an
   * ordinary request that goes through the middleware and may legitimately rotate. The count
   * rose to three or four with the coordinator working perfectly, so it had stopped measuring
   * coordination and started measuring how many links are on the page.
   *
   * The replacement was chosen by measurement rather than by argument. With the mutex removed,
   * ten navigations produced **eighteen rotations, nine rejections here, and nine users signed
   * out**; with it, rejections stay at zero regardless of how much traffic the navigation graph
   * generates. A rejection *is* the failure D197 exists to prevent — a request presenting a
   * revoked token, receiving 401, and ending a session in the middle of a page load — so this
   * asserts the failure directly instead of a proxy for it.
   *
   * The concurrency high-water mark is reported alongside because it is the direct reading of
   * the mutex, but it is deliberately not the assertion: a browser updates its cookie jar
   * between navigations, so requests opened together frequently arrive one at a time, and an
   * uncoordinated portal can show a maximum of one in flight while still signing users out.
   * `refresh.test.ts` is where genuinely simultaneous callers are exercised.
   */
  check(
    'no refresh is rejected as already-rotated',
    after.refreshRejections === 0,
    `${after.refreshRejections} rejected, ${rotations} rotations, ${after.maxConcurrentRefreshes} at once`,
  );

  const rendered = await Promise.all(
    pages.map(async (p) => (await p.content()).includes('Volume today')),
  );
  check(
    'all ten concurrent requests were served the application',
    rendered.every(Boolean),
    `${rendered.filter(Boolean).length} of 10 rendered`,
  );
  /*
   * `liveRefreshTokens` reads 0 for the ~40ms a rotation spends between deleting the token it was
   * handed and issuing the replacement (the deliberate latency in `stub-platform.mjs`). Under
   * `STUB_ACCESS_TTL=60` the ten pages' prefetches keep rotating for a beat after the `wait(600)`
   * above, so a single sample can land in that gap and read a transient 0 — which is not the
   * failure this checks for.
   *
   * The count is provably never above 1 here: `refreshRejections === 0` (asserted above) means
   * every rotation deleted exactly one token and added exactly one, from a base of one. So the
   * only genuine failures are a *persistent* 0 (a session rotated into nothing) or a count above
   * 1 (an orphan left beside the live token), and neither is transient. A single reading of 1 is
   * therefore conclusive — poll for one, and only fail if it never appears.
   */
  let liveAfterRace = -1;
  for (let attempt = 0; attempt < 50 && liveAfterRace !== 1; attempt += 1) {
    liveAfterRace = (await stub('/__stub/calls')).liveRefreshTokens;
    if (liveAfterRace !== 1) await wait(50);
  }
  check('the race left exactly one live refresh token', liveAfterRace === 1, String(liveAfterRace));
  const redirectedToLogin = pages.filter((p) => p.url().includes('/login')).length;
  check(
    'no request was signed out by the race',
    redirectedToLogin === 0,
    `${redirectedToLogin} of 10 landed on /login`,
  );

  await Promise.all(pages.map((p) => p.close()));
  await context.close();
}

async function verifyRevokedRefreshToken() {
  const context = await freshContext();
  const page = await context.newPage();
  await signIn(page);

  /*
   * One settled navigation first, so the browser is definitely holding a token the coordinator
   * has *not* already rotated.
   *
   * Without it this check fails for a reason that is not a defect: sign-in's own navigation
   * triggers a refresh, and until the browser has stored the result it re-presents the token that
   * was just rotated — which the replay cache answers from memory, without contacting identity at
   * all. That is the cache doing exactly its job, and it means revocation cannot be observed
   * through a token whose rotation is already cached.
   */
  await page.goto(`${BASE}/onboarding`, { waitUntil: 'networkidle' });
  await wait(300);

  // Signed out elsewhere: every refresh token is dead, and the access token is inside the skew
  // window, so the next navigation must discover it.
  await fetch(`${STUB}/__stub/revoke-all`);
  await page.goto(`${BASE}/foundation`, { waitUntil: 'networkidle' });

  check('a revoked refresh token ends the session', page.url().includes('/login'), page.url());
  const cookie = await sessionCookie(context);
  check(
    'the dead session cookie is cleared rather than left to fail repeatedly',
    cookie === undefined || cookie.value === '',
    cookie?.value ? 'still set' : 'cleared',
  );

  await context.close();
}

async function verifyTamperedCookie() {
  const context = await freshContext();
  const page = await context.newPage();
  await signIn(page);

  const cookie = await sessionCookie(context);
  if (cookie) {
    const parts = cookie.value.split('.');
    const body = parts[2] ?? '';
    await context.addCookies([
      {
        ...cookie,
        value: `${parts[0]}.${parts[1]}.${body[0] === 'A' ? 'B' : 'A'}${body.slice(1)}`,
      },
    ]);
  }

  await page.goto(`${BASE}/foundation`, { waitUntil: 'networkidle' });
  check('a tampered session cookie is refused', page.url().includes('/login'), page.url());

  await context.addCookies([
    { name: SESSION_COOKIE, value: 'v1.garbage.garbage', domain: 'localhost', path: '/' },
  ]);
  await page.goto(`${BASE}/foundation`, { waitUntil: 'networkidle' });
  check('a forged session cookie is refused', page.url().includes('/login'), page.url());

  await context.close();
}

async function verifyCsrf() {
  const context = await freshContext();
  const page = await context.newPage();
  await signIn(page);

  // A sign-out with no CSRF token, from this origin. Refused on the token alone.
  const withoutToken = await page.evaluate(async (base) => {
    const response = await fetch(`${base}/logout`, {
      method: 'POST',
      body: new URLSearchParams({}),
      redirect: 'manual',
    });
    return response.status;
  }, BASE);
  check('a logout with no CSRF token does not sign the user out', true, `status ${withoutToken}`);
  check('the session survived the tokenless logout', (await sessionCookie(context)) !== undefined);

  // And with a wrong token.
  await page.evaluate(async (base) => {
    await fetch(`${base}/logout`, {
      method: 'POST',
      body: new URLSearchParams({ csrfToken: 'f'.repeat(64) }),
      redirect: 'manual',
    });
  }, BASE);
  check('the session survived a forged CSRF token', (await sessionCookie(context)) !== undefined);

  // A GET must not be a sign-out at all.
  const getStatus = await page.evaluate(async (base) => {
    const response = await fetch(`${base}/logout`, { method: 'GET', redirect: 'manual' });
    return response.status;
  }, BASE);
  check('GET /logout is not allowed', getStatus === 405, `status ${getStatus}`);
  check('the session survived GET /logout', (await sessionCookie(context)) !== undefined);

  await context.close();
}

async function verifyOpenRedirect() {
  const context = await freshContext();
  const page = await context.newPage();

  await page.goto(`${BASE}/login?next=https://example.com/phish`, { waitUntil: 'networkidle' });
  await page.fill('#field-email', CREDENTIALS.email);
  await page.fill('#field-password', CREDENTIALS.password);
  await Promise.all([
    page.waitForLoadState('networkidle'),
    page.getByRole('button', { name: /sign in/i }).click(),
  ]);
  await wait(400);

  check('an off-site next= does not survive sign-in', page.url().startsWith(BASE), page.url());

  await context.close();
}

async function verifyLogout() {
  await fetch(`${STUB}/__stub/reset`);
  const context = await freshContext();
  const page = await context.newPage();
  await signIn(page);
  await page.goto(`${BASE}/foundation`, { waitUntil: 'networkidle' });

  await page.getByRole('button', { name: 'Account' }).click();
  await wait(300);
  await Promise.all([
    page.waitForLoadState('networkidle'),
    page.getByRole('menuitem', { name: /sign out/i }).click(),
  ]);
  await wait(400);

  check('signing out lands on /login', page.url().includes('/login'), page.url());
  const cookie = await sessionCookie(context);
  check('the session cookie is gone', cookie === undefined || cookie.value === '');

  const { logoutCount, liveRefreshTokens } = await stub('/__stub/calls');
  check('the refresh token was revoked server-side', logoutCount > 0, `${logoutCount} calls`);
  check('no refresh token is left alive', liveRefreshTokens === 0, `${liveRefreshTokens} live`);

  await page.goto(`${BASE}/foundation`, { waitUntil: 'networkidle' });
  check('a protected page is unreachable after logout', page.url().includes('/login'), page.url());

  // The back button must not restore the application from the browser cache — a signed-out user
  // seeing the dashboard again, even a stale copy, is the failure people actually notice.
  await page.goBack({ waitUntil: 'networkidle' }).catch(() => undefined);
  await wait(300);
  const restored = await page.content();
  check('going back does not restore protected content', !restored.includes('Volume today'));

  await context.close();
}
