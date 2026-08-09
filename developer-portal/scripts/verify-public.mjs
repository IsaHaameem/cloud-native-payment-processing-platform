/**
 * Browser verification of the public entry flow (M23.2a).
 *
 * ── What this proves that the unit suite cannot ───────────────────────────────────────
 *
 * `registration.test.ts` and `onboarding.test.ts` prove each step in isolation, with the step
 * before it mocked. The thing they structurally cannot prove is the *join*: that a real browser,
 * carrying real cookies, can start on the landing page having never seen this application and
 * arrive at a working dashboard without anyone intervening. That is the completion criterion the
 * milestone actually states, and it is one continuous journey through five routes, three
 * redirects and two cookie rewrites.
 *
 * So this walks it end to end, once, as a person would — and then walks the returning-user path,
 * which is a different route through the same code and is the one that regresses when the
 * merchant guard changes.
 *
 * ── Run ───────────────────────────────────────────────────────────────────────────────
 *
 *   node scripts/stub-platform.mjs &                     # the fake gateway
 *   PF_GATEWAY_URL=http://localhost:4600 \
 *   PORTAL_PUBLIC_ORIGIN=http://localhost:3601 \
 *   PORT=3601 npm start &                                # the portal under test
 *   BASE=http://localhost:3601 STUB=http://localhost:4600 node scripts/verify-public.mjs
 *
 * Like its siblings, it asserts its own instrument first: a headless run that cannot see the page
 * must fail loudly rather than report green.
 */

import { chromium } from 'playwright-core';

const BASE = process.env.BASE ?? 'http://localhost:3000';
const STUB = process.env.STUB ?? 'http://localhost:4600';

/** The established, already-onboarded account the auth suite also uses. */
const EXISTING = { email: 'ada@example.com', password: 'correct horse battery staple' };

/** Registered by this run, so the merchant-less branch is exercised by a genuinely new user. */
const NEW_USER = {
  name: 'Grace Hopper',
  email: 'grace@example.com',
  password: 'a very long correct password',
  business: 'Hopper Systems Ltd',
};

const checks = [];
const failures = [];

function check(name, condition, detail) {
  checks.push({ name, ok: Boolean(condition), detail });
  if (!condition) failures.push(`${name}${detail ? ` — ${detail}` : ''}`);
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch({ channel: 'chrome', headless: true });

try {
  await fetch(`${STUB}/__stub/reset`);
  await verifyInstrument();
  await verifyLandingPage();
  await verifyPublicNavigation();
  await verifyMobileNavigation();
  await verifyEntryPagesLinkToEachOther();
  await verifyNewUserJourney();
  await verifySignupFromASiblingLoopbackAddress();
  await verifyPasswordRecovery();
  await verifyReloadAndHistory();
  await verifyReturningUserGoesStraightToTheDashboard();
  await verifyDashboardIsNotPublic();
  await verifyBothThemes();
  await verifyReducedMotion();
} finally {
  await browser.close();
}

for (const { name, ok, detail } of checks) {
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}
if (failures.length > 0) {
  console.error(`\n${failures.length} public-flow check(s) failed:`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`\nAll ${checks.length} public-flow checks passed.`);

// ──────────────────────────────────────────────────────────────────────────────────────

async function freshContext(options = {}) {
  return browser.newContext({ viewport: { width: 1280, height: 900 }, ...options });
}

async function signIn(page, { email, password }) {
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  await page.fill('#field-email', email);
  await page.fill('#field-password', password);
  await page.getByRole('button', { name: /^sign in$/i }).click();
  // Waiting for the URL to leave /login rather than for a load state: sign-in answers with a
  // redirect carrying the session cookie, and navigating away before that response is consumed
  // discards it. See verify-auth.mjs for the full account of that failure.
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15_000 });
  await page.waitForLoadState('networkidle');
}

async function verifyInstrument() {
  const context = await freshContext();
  const page = await context.newPage();
  await page.goto(BASE, { waitUntil: 'networkidle' });

  const state = await page.evaluate(() => document.visibilityState);
  check('the browser is visible and compositing', state === 'visible', state);
  check(
    'the portal is serving the landing page',
    await page.getByRole('heading', { level: 1 }).isVisible(),
  );
  await context.close();
}

async function verifyLandingPage() {
  const context = await freshContext();
  const page = await context.newPage();
  await page.goto(BASE, { waitUntil: 'networkidle' });

  const heading = await page.getByRole('heading', { level: 1 }).innerText();
  check('the hero sells the product', /payments infrastructure/i.test(heading), heading);

  const body = await page.content();
  // The specific regression this exists to prevent: the landing page describing our schedule.
  check('no milestone identifier is visible to a visitor', !/\bM2[3-9](\.\d)?\b/.test(body));
  check(
    'the old design-system link is not the public entry point',
    !body.includes('>Design system<'),
  );

  check(
    'the primary call to action goes to signup',
    (await page
      .getByRole('link', { name: /get started/i })
      .first()
      .getAttribute('href')) === '/signup',
  );
  check(
    'the secondary call to action goes to a section that exists',
    (await page.getByRole('link', { name: /see how it works/i }).getAttribute('href')) ===
      '#lifecycle',
  );

  for (const id of ['platform', 'lifecycle', 'developers', 'reliability']) {
    check(`the #${id} section exists`, (await page.locator(`#${id}`).count()) === 1);
  }

  // The scroll-reveal sections must actually become visible — a reveal that never fires is a
  // blank page, and it is invisible to a test that only asserts the markup is present.
  await page.locator('#reliability').scrollIntoViewIfNeeded();
  await wait(500);
  check(
    'scrolled-to sections are revealed, not left at zero opacity',
    await page.locator('#reliability').getByText('Circuit breakers', { exact: true }).isVisible(),
  );

  await context.close();
}

async function verifyPublicNavigation() {
  const context = await freshContext();
  const page = await context.newPage();
  await page.goto(BASE, { waitUntil: 'networkidle' });

  const nav = page.getByRole('navigation', { name: 'Site' }).first();
  const hrefs = await nav
    .locator('a')
    .evaluateAll((links) => links.map((link) => link.getAttribute('href')));
  check('the navbar has navigation', hrefs.length >= 4, hrefs.join(' '));
  // Every destination is either a section of this page or a route that exists. Nothing dead.
  check(
    'no navbar link points at a route that does not exist',
    hrefs.every((href) => href?.startsWith('#')),
    hrefs.join(' '),
  );

  check(
    'signed-out visitors are offered sign in',
    (await page
      .getByRole('link', { name: /^sign in$/i })
      .first()
      .getAttribute('href')) === '/login',
  );

  // Following an anchor must land on the section, not behind the sticky header.
  await page.getByRole('link', { name: 'Lifecycle' }).first().click();
  await wait(600);
  const top = await page.locator('#lifecycle').evaluate((el) => el.getBoundingClientRect().top);
  check('an in-page link clears the sticky navbar', top >= 0 && top < 200, `${Math.round(top)}px`);

  await context.close();
}

async function verifyMobileNavigation() {
  const context = await freshContext({ viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  await page.goto(BASE, { waitUntil: 'networkidle' });

  /*
   * Scoped to the header, and that is not incidental.
   *
   * `getByRole` reads the accessibility tree, which excludes `display:none` — so an unscoped
   * `getByRole('link', { name: 'Lifecycle' })` does not match the hidden desktop nav at all and
   * silently resolves to the *footer's* copy of the same label, which is visible at every width.
   * The check then asserts nothing. Naming the header is what keeps this measuring the navbar.
   */
  const headerLink = page.locator('header').getByRole('link', { name: 'Lifecycle' });

  check('the desktop nav is hidden on a phone', (await headerLink.count()) === 0);

  const toggle = page.getByRole('button', { name: /open menu/i });
  check('a menu button is offered instead', await toggle.isVisible());

  await toggle.click();
  await wait(300);
  check('the drawer opens and carries the same destinations', await headerLink.isVisible());
  check(
    'the trigger reports its state to assistive technology',
    (await page.getByRole('button', { name: /close menu/i }).getAttribute('aria-expanded')) ===
      'true',
  );

  await headerLink.click();
  await wait(400);
  check('following a link closes the drawer', (await headerLink.count()) === 0);

  // The requirement that survives every redesign: the page must not scroll sideways.
  const overflows = await page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth + 1,
  );
  check('the landing page does not scroll horizontally at 390px', !overflows);

  await context.close();
}

async function verifyEntryPagesLinkToEachOther() {
  const context = await freshContext();
  const page = await context.newPage();

  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  await page.getByRole('link', { name: /create one/i }).click();
  await page.waitForURL(/\/signup/);
  check('login offers a way to create an account', page.url().includes('/signup'), page.url());

  await page.getByRole('link', { name: /^sign in$/i }).click();
  await page.waitForURL(/\/login/);
  check('signup offers a way back to sign in', page.url().includes('/login'), page.url());

  await context.close();
}

async function verifyNewUserJourney() {
  const context = await freshContext();
  const page = await context.newPage();

  // 1 — landing to signup, the way a visitor would.
  await page.goto(BASE, { waitUntil: 'networkidle' });
  await page
    .getByRole('link', { name: /get started/i })
    .first()
    .click();
  await page.waitForURL(/\/signup/);
  check('the landing CTA reaches signup', page.url().includes('/signup'), page.url());

  // 2 — the password rules are stated before they are broken.
  await page.fill('#field-fullName', NEW_USER.name);
  await page.fill('#field-email', NEW_USER.email);
  await page.fill('#field-password', 'short');
  await page.getByRole('button', { name: /create account/i }).click();
  await wait(600);
  check('a short password is refused', page.url().includes('/signup'), page.url());

  // 3 — the reveal toggle is a real control, not decoration.
  await page.getByRole('button', { name: /show password/i }).click();
  check(
    'the password can be revealed',
    (await page.locator('#field-password').getAttribute('type')) === 'text',
  );
  await page.getByRole('button', { name: /hide password/i }).click();
  check(
    'and hidden again',
    (await page.locator('#field-password').getAttribute('type')) === 'password',
  );

  // 4 — a real registration.
  await page.fill('#field-password', NEW_USER.password);
  await page.getByRole('button', { name: /create account/i }).click();
  await page.waitForURL(/\/login/, { timeout: 15_000 });
  await page.waitForLoadState('networkidle');

  check('registration hands the user to sign in', page.url().includes('/login'), page.url());
  check(
    'and says so',
    /account created/i.test(await page.locator('[role="alert"]').first().innerText()),
  );
  check(
    'the address is carried over rather than retyped',
    (await page.locator('#field-email').inputValue()) === NEW_USER.email,
  );

  // 5 — signing in with no merchant lands on onboarding, not on a dashboard with nothing in it.
  await page.fill('#field-password', NEW_USER.password);
  await page.getByRole('button', { name: /^sign in$/i }).click();
  /*
   * Waiting for `/onboarding` specifically, not merely for "not /login".
   *
   * Sign-in here carries `next=/dashboard` from the deep link the signup redirect preserved, so
   * the browser passes *through* `/dashboard` on its way to the merchant guard's redirect. A
   * wait that stops at the first non-login URL therefore reads the intermediate hop and reports
   * a failure the user never experiences.
   */
  await page.waitForURL(/\/onboarding/, { timeout: 15_000 });
  await page.waitForLoadState('networkidle');

  check(
    'a user with no merchant is routed to onboarding',
    page.url().includes('/onboarding'),
    page.url(),
  );
  check(
    'onboarding explains itself rather than apologising',
    /set up your business/i.test(await page.getByRole('heading', { level: 1 }).innerText()),
  );
  check(
    'the contact address is pre-filled from the account',
    (await page.locator('#field-contactEmail').inputValue()) === NEW_USER.email,
  );
  // Onboarding must not be framed by a dashboard the user cannot yet use.
  check(
    'onboarding is not wrapped in the application shell',
    (await page.getByRole('navigation', { name: 'Main' }).count()) === 0,
  );

  // 6 — onboarding, for real.
  await page.fill('#field-businessName', NEW_USER.business);
  await page.getByRole('button', { name: /create business account/i }).click();
  await page.waitForURL(/\/dashboard/, { timeout: 15_000 });
  await page.waitForLoadState('networkidle');

  check('onboarding lands on the dashboard', page.url().includes('/dashboard'), page.url());
  check(
    'the dashboard names the business that was just created',
    (await page.content()).includes(NEW_USER.business),
  );
  check(
    'the application shell is present now that there is an application',
    (await page.getByRole('navigation', { name: 'Main' }).count()) > 0,
  );

  // 7 — and the credential promise still holds on the new surfaces.
  const storage = await page.evaluate(() => ({
    cookie: document.cookie,
    local: JSON.stringify(localStorage),
    session: JSON.stringify(sessionStorage),
  }));
  check('document.cookie still cannot see the session', !storage.cookie.includes('pf_session'));
  check('localStorage holds no credential', !/token/i.test(storage.local), storage.local);
  check('sessionStorage holds no credential', !/token/i.test(storage.session), storage.session);
  check(
    'no JWT appears in the dashboard HTML',
    !/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\./.test(await page.content()),
  );

  // 8 — revisiting onboarding after finishing it must not offer a second merchant.
  await page.goto(`${BASE}/onboarding`, { waitUntil: 'networkidle' });
  check(
    'onboarding sends a finished merchant back to the dashboard',
    page.url().includes('/dashboard'),
    page.url(),
  );

  // 9 — and out.
  await page.getByRole('button', { name: 'Account' }).click();
  await wait(300);
  await Promise.all([
    page.waitForLoadState('networkidle'),
    page.getByRole('menuitem', { name: /sign out/i }).click(),
  ]);
  await wait(400);
  check('signing out lands on /login', page.url().includes('/login'), page.url());

  await context.close();
}

/**
 * The bug this milestone was opened for, at the layer it was reported from (M23.2b).
 *
 * The portal is configured for `http://localhost:…` and reached at `http://127.0.0.1:…` — the
 * same server, the same machine, the spelling a browser will offer by itself. Every state-changing
 * form was refused, and the screen said the form had expired.
 *
 * This is deliberately a *browser* check and not only a unit one: the unit test proves the
 * decision, and this proves the decision is the one actually reached by a real form post carrying
 * a real `Origin` header through the real middleware.
 */
async function verifySignupFromASiblingLoopbackAddress() {
  const sibling = BASE.replace('localhost', '127.0.0.1');
  if (sibling === BASE) {
    check('the sibling-loopback check has an address to use', false, `BASE is ${BASE}`);
    return;
  }

  const context = await freshContext();
  const page = await context.newPage();

  await page.goto(`${sibling}/signup`, { waitUntil: 'networkidle' });
  await page.fill('#field-email', `loopback-${Date.now()}@example.com`);
  await page.fill('#field-password', 'a very long correct password');
  await page.getByRole('button', { name: /create account/i }).click();
  await page.waitForURL(/\/login/, { timeout: 15_000 });

  check(
    'signup works when the portal is reached at 127.0.0.1 instead of localhost',
    page.url().includes('registered=1'),
    page.url(),
  );

  // And the message that made this undiagnosable is gone from the successful path entirely.
  const body = await page.content();
  check('no "form expired" message is shown on a successful signup', !/form expired/i.test(body));

  await context.close();
}

/**
 * Password recovery end to end (M23.2b), against the endpoints identity-service has served since
 * M15 — including the parts only a browser can show: that the emailed link lands on a working
 * page, that the new password actually works, and that the old one no longer does.
 *
 * The token comes from the stub's `/__stub/reset-token`, which stands in for the mailbox. There
 * is no other way to obtain it: the real one is delivered by notification-service over Kafka, and
 * the portal never sees it.
 */
async function verifyPasswordRecovery() {
  await fetch(`${STUB}/__stub/reset`);
  const context = await freshContext();
  const page = await context.newPage();

  const email = 'ada@example.com';
  const newPassword = 'an entirely new long password';

  // 1 — the entry point exists on the page someone stuck at sign-in is looking at.
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  const forgot = page.getByRole('link', { name: /forgot password/i });
  check('sign-in offers a way to recover a password', await forgot.isVisible());

  await forgot.click();
  await page.waitForURL(/\/forgot-password/);
  await page.waitForLoadState('networkidle');

  // 2 — an address that does not exist must be answered exactly like one that does.
  await page.fill('#field-email', 'nobody-at-all@example.com');
  await page.getByRole('button', { name: /send reset link/i }).click();
  await wait(800);
  const unknownAnswer = (await page.locator('main, body').first().innerText()).trim();
  check(
    'an unknown address is confirmed rather than refused',
    /check your inbox/i.test(unknownAnswer),
    unknownAnswer.slice(0, 60),
  );
  const unknownToken = (
    await (await fetch(`${STUB}/__stub/reset-token?email=nobody-at-all@example.com`)).json()
  ).token;
  check('and no token is issued for it', unknownToken === null, String(unknownToken));

  // 3 — the real request.
  await page.goto(`${BASE}/forgot-password`, { waitUntil: 'networkidle' });
  await page.fill('#field-email', email);
  await page.getByRole('button', { name: /send reset link/i }).click();
  await wait(800);
  const knownAnswer = (await page.locator('main, body').first().innerText()).trim();
  check(
    'a known address gets the identical confirmation',
    /check your inbox/i.test(knownAnswer),
    knownAnswer.slice(0, 60),
  );

  const { token } = await (await fetch(`${STUB}/__stub/reset-token?email=${email}`)).json();
  check(
    'a reset token was issued for the known address',
    typeof token === 'string' && token.length > 0,
  );

  // 4 — a link with no token explains itself rather than rendering a form that cannot work.
  await page.goto(`${BASE}/reset-password`, { waitUntil: 'networkidle' });
  check(
    'a truncated link is explained, not rendered as a broken form',
    /link is incomplete/i.test(await page.locator('body').innerText()),
  );
  check(
    'and offers a way to get a new one',
    (await page.getByRole('link', { name: /request a new link/i }).count()) > 0,
  );

  // 5 — a made-up token is refused on submit, which is the earliest it can honestly be known.
  await page.goto(`${BASE}/reset-password?token=not-a-real-token`, { waitUntil: 'networkidle' });
  await page.fill('#field-password', newPassword);
  await page.fill('#field-confirm', newPassword);
  await page.getByRole('button', { name: /set new password/i }).click();
  await wait(800);
  check(
    'an invalid token is refused',
    /no longer valid/i.test(await page.locator('[role="alert"]').first().innerText()),
  );

  // 6 — the real reset.
  await page.goto(`${BASE}/reset-password?token=${encodeURIComponent(token)}`, {
    waitUntil: 'networkidle',
  });
  await page.fill('#field-password', newPassword);
  await page.fill('#field-confirm', 'a completely different password');
  await page.getByRole('button', { name: /set new password/i }).click();
  await wait(800);
  check(
    'a mismatched confirmation is caught',
    /do not match/i.test(await page.locator('body').innerText()),
  );

  await page.fill('#field-password', newPassword);
  await page.fill('#field-confirm', newPassword);
  await page.getByRole('button', { name: /set new password/i }).click();
  await page.waitForURL(/\/login/, { timeout: 15_000 });
  await page.waitForLoadState('networkidle');

  check('a successful reset lands on sign in', page.url().includes('reset=1'), page.url());
  check(
    'and says the account was signed out everywhere',
    /signed out everywhere/i.test(await page.locator('body').innerText()),
  );

  // 7 — the token is single-use, exactly as `PasswordResetService.confirmReset` makes it.
  await page.goto(`${BASE}/reset-password?token=${encodeURIComponent(token)}`, {
    waitUntil: 'networkidle',
  });
  await page.fill('#field-password', 'yet another long password');
  await page.fill('#field-confirm', 'yet another long password');
  await page.getByRole('button', { name: /set new password/i }).click();
  await wait(800);
  check(
    'a reset token cannot be used twice',
    /no longer valid/i.test(await page.locator('[role="alert"]').first().innerText()),
  );

  // 8 — the password actually changed, in both directions.
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  await page.fill('#field-email', email);
  await page.fill('#field-password', EXISTING.password);
  await page.getByRole('button', { name: /^sign in$/i }).click();
  await wait(1000);
  check('the old password no longer works', page.url().includes('/login'), page.url());

  await signIn(page, { email, password: newPassword });
  check('the new password does', !page.url().includes('/login'), page.url());

  await context.close();
  // Left as it was found: every later check signs in with the seeded credentials.
  await fetch(`${STUB}/__stub/reset`);
}

/**
 * Reloading and moving through history on the entry pages (M23.2b).
 *
 * A synchronizer token makes both of these worth checking rather than assuming. The token is
 * rendered into the form, so a page restored from the back/forward cache could in principle carry
 * a stale one — and the symptom would be precisely the "this form expired" the user reported for a
 * different reason. These prove the ordinary browser gestures leave a working form behind.
 */
async function verifyReloadAndHistory() {
  await fetch(`${STUB}/__stub/reset`);
  const context = await freshContext();
  const page = await context.newPage();

  const tokenOn = (p) => p.locator('input[name="csrfToken"]').first().inputValue();
  const cookieValue = async () =>
    (await context.cookies()).find((c) => c.name === 'pf_csrf')?.value ?? '';

  await page.goto(`${BASE}/signup`, { waitUntil: 'networkidle' });
  await page.reload({ waitUntil: 'networkidle' });
  check(
    'a reloaded signup form carries the live token',
    (await tokenOn(page)) === (await cookieValue()),
  );

  // Navigate away and back, which is where a cached document would surface.
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  await page.goBack({ waitUntil: 'networkidle' });
  await wait(300);
  check(
    'going back to signup leaves a submittable form',
    (await tokenOn(page)) === (await cookieValue()),
    page.url(),
  );

  // And it must actually submit, not merely look right.
  await page.fill('#field-email', `history-${Date.now()}@example.com`);
  await page.fill('#field-password', 'a very long correct password');
  await page.getByRole('button', { name: /create account/i }).click();
  await page.waitForURL(/\/login/, { timeout: 15_000 });
  check('and it registers', page.url().includes('registered=1'), page.url());

  await page.goForward({ waitUntil: 'networkidle' }).catch(() => undefined);
  await wait(300);
  check(
    'forward navigation does not strand the user',
    !(await page.content()).includes('form expired'),
  );

  await context.close();
  await fetch(`${STUB}/__stub/reset`);
}

async function verifyReturningUserGoesStraightToTheDashboard() {
  const context = await freshContext();
  const page = await context.newPage();

  await signIn(page, EXISTING);
  check(
    'a user who already has a merchant skips onboarding',
    page.url().includes('/dashboard'),
    page.url(),
  );
  check(
    'the shell names the business they are acting as',
    (await page.content()).includes('Ada Lovelace Ltd'),
  );

  // The landing page must recognise them rather than selling them an account they have.
  await page.goto(BASE, { waitUntil: 'networkidle' });
  check(
    'the public navbar offers a signed-in visitor their dashboard',
    (await page
      .locator('header')
      .getByRole('link', { name: /go to dashboard/i })
      .getAttribute('href')) === '/dashboard',
  );
  // Not just the navbar: the hero and the closing band must stop selling an account too, since
  // the middleware redirects a signed-in visitor away from `/signup` — a "Get started" button
  // left in place is a control that visibly does something other than what it says.
  check(
    'and no call to action anywhere offers them a second account',
    (await page.getByRole('link', { name: /get started|create your account/i }).count()) === 0,
  );
  check(
    'no link on the page points at signup',
    (await page.locator('a[href="/signup"]').count()) === 0,
  );

  // Both entry pages are dead ends for someone already signed in.
  for (const entry of ['/login', '/signup']) {
    await page.goto(`${BASE}${entry}`, { waitUntil: 'networkidle' });
    check(
      `a signed-in visitor is redirected away from ${entry}`,
      !page.url().includes(entry),
      page.url(),
    );
  }

  await context.close();
}

async function verifyDashboardIsNotPublic() {
  const context = await freshContext();
  const page = await context.newPage();

  await page.goto(`${BASE}/dashboard`, { waitUntil: 'networkidle' });
  check('the dashboard redirects a stranger to sign in', page.url().includes('/login'), page.url());
  check(
    'the redirect remembers where they were going',
    decodeURIComponent(page.url()).includes('next=/dashboard'),
    page.url(),
  );
  // Not merely replaced quickly — never rendered.
  check(
    'no dashboard content is served to a stranger',
    !(await page.content()).includes('Getting started'),
  );

  await page.goto(`${BASE}/onboarding`, { waitUntil: 'networkidle' });
  check('onboarding is protected too', page.url().includes('/login'), page.url());

  await context.close();
}

async function verifyBothThemes() {
  for (const theme of ['dark', 'light']) {
    const context = await freshContext();
    const page = await context.newPage();

    // next-themes stores the choice here; setting it before the first paint is what makes this
    // a test of the light theme rather than of a flash of the dark one.
    await page.addInitScript((value) => window.localStorage.setItem('theme', value), theme);
    await page.goto(BASE, { waitUntil: 'networkidle' });
    await wait(300);

    const background = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    const heading = await page.evaluate(() => {
      const h1 = document.querySelector('h1');
      return h1 ? getComputedStyle(h1).color : '';
    });

    check(`the ${theme} theme paints a background`, background !== 'rgba(0, 0, 0, 0)', background);
    check(
      `the ${theme} theme's hero is legible against it`,
      contrast(background, heading) >= 4.5,
      `${contrast(background, heading).toFixed(2)}:1`,
    );

    await context.close();
  }
}

async function verifyReducedMotion() {
  const context = await freshContext({ reducedMotion: 'reduce' });
  const page = await context.newPage();
  await page.goto(BASE, { waitUntil: 'networkidle' });

  // The failure this catches is specific and severe: a scroll-reveal whose *only* mechanism is a
  // transform leaves the whole page at zero opacity when motion is reduced. Every section must
  // still arrive.
  await page.locator('#reliability').scrollIntoViewIfNeeded();
  await wait(400);

  const opacity = await page.locator('#reliability').evaluate((el) => getComputedStyle(el).opacity);
  check('reduced motion still reveals the content', Number(opacity) > 0.99, opacity);
  check(
    'and the copy is there to read',
    await page.locator('#reliability').getByText('Circuit breakers', { exact: true }).isVisible(),
  );

  await context.close();
}

/** WCAG relative-luminance contrast, for the two colours the theme check reads off the page. */
function contrast(a, b) {
  const luminance = (color) => {
    const [r, g, b2] = (color.match(/\d+(\.\d+)?/g) ?? ['0', '0', '0']).slice(0, 3).map(Number);
    const channel = (value) => {
      const v = value / 255;
      return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
    };
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b2);
  };
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}
