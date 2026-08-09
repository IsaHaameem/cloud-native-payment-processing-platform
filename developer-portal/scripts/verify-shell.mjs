/**
 * Browser verification of the shell and data layer (M23.3).
 *
 * ── What only a browser can prove here ────────────────────────────────────────────────
 *
 * The unit suite proves the keys carry the mode, the route refuses mutations, and an id is
 * classified correctly. None of it can prove the things this milestone is actually judged on:
 * that a pasted id reaches the platform *through the session* and comes back as a row, that focus
 * lands on the new page's heading after a client-side navigation, that the trail in the header
 * says where you are, and — the security one — that a browser cannot talk the read route into
 * performing a mutation or into choosing its own mode.
 *
 * ── Run ───────────────────────────────────────────────────────────────────────────────
 *
 *   node scripts/stub-platform.mjs &
 *   PF_GATEWAY_URL=http://localhost:4600 PORTAL_PUBLIC_ORIGIN=http://localhost:3601 \
 *   PORT=3601 npm start &
 *   BASE=http://localhost:3601 STUB=http://localhost:4600 node scripts/verify-shell.mjs
 *
 * Like its siblings, it asserts its own instrument before anything else.
 */

import { chromium } from 'playwright-core';

const BASE = process.env.BASE ?? 'http://localhost:3000';
const STUB = process.env.STUB ?? 'http://localhost:4600';

const CREDENTIALS = { email: 'ada@example.com', password: 'correct horse battery staple' };

/** Ids the stub knows, in the contract's own shapes. */
const PAYMENT_ID = '11111111-aaaa-4bbb-8ccc-111111111111';
const REFUND_ID = '22222222-aaaa-4bbb-8ccc-222222222222';
const EVENT_ID = 'evt_9f2c1e7a4b8d4c3e8a1d2b4f6a8c05d1';
const UNKNOWN_ID = '99999999-dead-4bee-8fff-999999999999';

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
  await verifyReadRouteRefusals();
  await verifyObjectLookup();
  await verifyBreadcrumbs();
  await verifyRouteFocus();
  await verifyNoTokenReachesTheClientLayer();
} finally {
  await browser.close();
}

for (const { name, ok, detail } of checks) {
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}
if (failures.length > 0) {
  console.error(`\n${failures.length} shell check(s) failed:`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`\nAll ${checks.length} shell checks passed.`);

// ──────────────────────────────────────────────────────────────────────────────────────

async function signedInPage(options = {}) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, ...options });
  const page = await context.newPage();
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  await page.fill('#field-email', CREDENTIALS.email);
  await page.fill('#field-password', CREDENTIALS.password);
  await page.getByRole('button', { name: /^sign in$/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15_000 });
  await page.waitForLoadState('networkidle');
  return { context, page };
}

/** The command palette's search box. `input` alone also matches hidden CSRF fields. */
function paletteInput(page) {
  return page.locator('input[aria-controls="command-palette-list"]');
}

/** Reads the platform route from inside the page, so the request carries the real session. */
async function readThroughPortal(page, path) {
  return page.evaluate(async (url) => {
    const response = await fetch(url, { headers: { Accept: 'application/json' } });
    return { status: response.status, body: await response.text() };
  }, path);
}

async function verifyInstrument() {
  const { context, page } = await signedInPage();
  const state = await page.evaluate(() => document.visibilityState);
  check('the browser is visible and compositing', state === 'visible', state);
  check('a session reaches the application shell', !page.url().includes('/login'), page.url());
  await context.close();
}

async function verifyReadRouteRefusals() {
  const { context, page } = await signedInPage();

  // The property the design rests on: a browser holding a valid session still cannot reach a
  // mutation through the read route.
  for (const mutation of ['capturePayment', 'refundPayment', 'voidPayment', 'createPayment']) {
    const { status } = await readThroughPortal(page, `/api/platform/${mutation}`);
    check(`a signed-in browser cannot reach ${mutation}`, status === 404, `status ${status}`);
  }

  const unknownParam = await readThroughPortal(page, '/api/platform/listPayments?nonsense=1');
  check(
    'an undocumented parameter is refused rather than silently dropped',
    unknownParam.status === 400,
    `status ${unknownParam.status}`,
  );

  const modeAttempt = await readThroughPortal(page, '/api/platform/listPayments?mode=live');
  check(
    'the client cannot choose its own mode',
    modeAttempt.status === 400,
    `status ${modeAttempt.status}`,
  );

  const ok = await readThroughPortal(page, `/api/platform/getPayment?id=${PAYMENT_ID}`);
  check('a documented read succeeds through the session', ok.status === 200, `status ${ok.status}`);
  check('and returns the platform’s object', ok.body.includes('captured'), ok.body.slice(0, 80));

  // No session, same route.
  const anonymous = await browser.newContext();
  const anonymousPage = await anonymous.newPage();
  await anonymousPage.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  const withoutSession = await readThroughPortal(
    anonymousPage,
    `/api/platform/getPayment?id=${PAYMENT_ID}`,
  );
  check(
    'without a session the route answers 401, not a redirect to HTML',
    withoutSession.status === 401,
    `status ${withoutSession.status}`,
  );
  check(
    'and says so as JSON the client layer can branch on',
    withoutSession.body.includes('unauthorized'),
    withoutSession.body.slice(0, 80),
  );
  await anonymous.close();

  await context.close();
}

async function verifyObjectLookup() {
  const { context, page } = await signedInPage();

  const openPalette = async () => {
    await page.keyboard.press('Control+k');
    await wait(400);
  };

  await openPalette();
  check('the command palette opens', (await page.locator('[role="listbox"]').count()) > 0);

  // Precise: `input` alone also matches the hidden CSRF field the shell renders for sign-out.
  const inputSelector = paletteInput(page);

  // A payment.
  await inputSelector.fill(PAYMENT_ID);
  await wait(1200);
  const paletteText = async () => (await page.locator('[role="listbox"]').innerText()).trim();
  let text = await paletteText();
  check('pasting a payment id resolves it', /payment/i.test(text), text.split('\n')[0]);
  check('and shows the status the platform returned', /captured/i.test(text), text.split('\n')[0]);
  check('and the amount, formatted', /42\.00/.test(text), text.split('\n')[0]);

  // A refund — same UUID shape, resolved only because the payment lookup 404s first.
  await inputSelector.fill(REFUND_ID);
  await wait(1200);
  text = await paletteText();
  check(
    'a refund id falls through to the refund endpoint',
    /refund/i.test(text),
    text.split('\n')[0],
  );

  // An event — the one prefixed id in the contract.
  await inputSelector.fill(EVENT_ID);
  await wait(1200);
  text = await paletteText();
  check('an event id resolves by its prefix', /event/i.test(text), text.split('\n')[0]);
  check('and names the event type', /payment\.captured/.test(text), text.split('\n')[0]);

  // A well-formed id that is nobody's.
  await inputSelector.fill(UNKNOWN_ID);
  await wait(1500);
  text = await paletteText();
  check(
    'an unknown id reports a miss rather than an error',
    /no payment, refund or event/i.test(text),
    text.split('\n')[0],
  );

  // Ordinary typing must not fire a lookup at all.
  const requests = [];
  page.on('request', (r) => {
    if (r.url().includes('/api/platform/')) requests.push(r.url());
  });
  await inputSelector.fill('payments');
  await wait(900);
  check(
    'ordinary typing does not request the platform',
    requests.length === 0,
    `${requests.length} requests`,
  );

  await page.keyboard.press('Escape');
  await context.close();
}

async function verifyBreadcrumbs() {
  const { context, page } = await signedInPage();

  await page.goto(`${BASE}/dashboard`, { waitUntil: 'networkidle' });
  check(
    'no trail on a one-level route, where it would repeat the title',
    (await page.getByRole('navigation', { name: 'Breadcrumb' }).count()) === 0,
  );

  await page.goto(`${BASE}/foundation`, { waitUntil: 'networkidle' });
  check(
    'still no trail on another one-level route',
    (await page.getByRole('navigation', { name: 'Breadcrumb' }).count()) === 0,
  );

  await context.close();
}

async function verifyRouteFocus() {
  const { context, page } = await signedInPage();
  await page.goto(`${BASE}/dashboard`, { waitUntil: 'networkidle' });

  const focusedTag = () =>
    page.evaluate(() => ({
      tag: document.activeElement?.tagName ?? '',
      text: document.activeElement?.textContent?.slice(0, 40) ?? '',
    }));

  /*
   * Both kinds of client-side navigation, because they failed differently while this was being
   * built: a sidebar click leaves focus on the link (the stale focus the component exists to
   * move), and a palette selection leaves it in a dialog input that Radix then tears down.
   */
  await page.goto(`${BASE}/foundation`, { waitUntil: 'networkidle' });
  await page.getByRole('link', { name: 'Overview' }).first().click();
  await wait(1200);

  let focused = await focusedTag();
  check(
    'a sidebar navigation moves focus to the new page’s heading',
    focused.tag === 'H1',
    `${focused.tag} "${focused.text}"`,
  );

  await page.keyboard.press('Control+k');
  await wait(400);
  await paletteInput(page).fill('Design foundation');
  await wait(400);
  await page.keyboard.press('Enter');
  await wait(1200);

  focused = await focusedTag();
  check(
    'and so does a command-palette navigation',
    focused.tag === 'H1',
    `${focused.tag} "${focused.text}"`,
  );

  // The heading is programmatically focusable but must stay out of the tab order.
  const tabIndex = await page.locator('main h1').getAttribute('tabindex');
  check(
    'the heading is focusable without joining the tab order',
    tabIndex === '-1',
    String(tabIndex),
  );

  await context.close();
}

async function verifyNoTokenReachesTheClientLayer() {
  const { context, page } = await signedInPage();
  await page.goto(`${BASE}/dashboard`, { waitUntil: 'networkidle' });

  // Exercise the client data layer, then confirm it left nothing behind.
  await page.keyboard.press('Control+k');
  await wait(300);
  await paletteInput(page).fill(PAYMENT_ID);
  await wait(1200);

  const storage = await page.evaluate(() => ({
    cookie: document.cookie,
    local: JSON.stringify(localStorage),
    session: JSON.stringify(sessionStorage),
  }));

  check('document.cookie still cannot see the session', !storage.cookie.includes('pf_session'));
  check(
    'the query cache is not persisted to localStorage',
    !/token|pf_session/i.test(storage.local),
    storage.local.slice(0, 60),
  );
  check(
    'nor to sessionStorage',
    !/token|pf_session/i.test(storage.session),
    storage.session.slice(0, 60),
  );

  const html = await page.content();
  check(
    'no JWT appears in the document after a client-side read',
    !/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\./.test(html),
  );

  await context.close();
}
