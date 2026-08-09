/**
 * Browser verification of the payments list (M23.6).
 *
 * ── What only a browser can prove ─────────────────────────────────────────────────────
 *
 * `payments.test.ts` proves the filter model: URLs round-trip, only documented parameters are
 * sent, keys carry the scope. What it cannot prove is that the cursor actually advances against a
 * live query layer, that a filter change reaches the platform and comes back narrower, that the
 * table keeps its geometry while it does, and — the security half — that switching mode replaces
 * every row rather than showing one mode's money under the other's heading.
 *
 * ── Every block signs in for itself ───────────────────────────────────────────────────
 *
 * The same discipline as the settings and API-key suites, for the same measured reason: the stub
 * runs a sixty-second access-token life for the authentication suite's benefit, and a long journey
 * under constant rotation eventually renders after a refused lookup. This suite raises the TTL for
 * its own duration and restores it in the `finally`.
 *
 * ── Run ───────────────────────────────────────────────────────────────────────────────
 *
 *   node scripts/stub-platform.mjs &
 *   PF_GATEWAY_URL=http://localhost:4600 PORTAL_PUBLIC_ORIGIN=http://localhost:3601 \
 *   PORT=3601 npm start &
 *   BASE=http://localhost:3601 STUB=http://localhost:4600 node scripts/verify-payments.mjs
 */

import { chromium } from 'playwright-core';

const BASE = process.env.BASE ?? 'http://localhost:3000';
const STUB = process.env.STUB ?? 'http://localhost:4600';
const ROUTE = '/payments';

const CREDENTIALS = { email: 'ada@example.com', password: 'correct horse battery staple' };

const checks = [];
const failures = [];

function check(name, condition, detail) {
  checks.push({ name, ok: Boolean(condition), detail });
  if (!condition) failures.push(`${name}${detail ? ` — ${detail}` : ''}`);
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitFor(predicate, timeout = 20_000) {
  const deadline = Date.now() + timeout;
  for (;;) {
    if (await predicate()) return true;
    if (Date.now() > deadline) return false;
    await wait(100);
  }
}

/** The data rows, excluding the skeleton's placeholder rows. */
const rowCount = (page) => page.locator('table tbody tr').count();
/**
 * The full payment ids on screen.
 *
 * Read from the cell's `title`, not its text: the column shows a truncated id by design, and for a
 * bare UUID that is only the last six characters — identical across the stub's two ledgers, which
 * would make a mode-isolation assertion on the visible text pass for the wrong reason.
 */
const idCells = (page) =>
  page
    .locator('table tbody tr td:first-child [title]')
    .evaluateAll((nodes) => nodes.map((node) => node.getAttribute('title')));
const mainText = (page) => page.locator('main').innerText();

const browser = await chromium.launch({ channel: 'chrome', headless: true });

async function section(name, fn) {
  try {
    await fn();
  } catch (error) {
    check(`${name} completed`, false, String(error).slice(0, 140));
  }
}

try {
  await fetch(`${STUB}/__stub/reset`);
  await fetch(`${STUB}/__stub/access-ttl?seconds=900`);

  await section('instrument', verifyInstrument);
  await section('first page', verifyFirstPage);
  await section('pagination', verifyCursorPagination);
  await section('filtering', verifyFiltering);
  await section('shareable view', verifyTheUrlIsTheView);
  await section('no matches', verifyNoMatches);
  await section('errors', verifyErrorHandling);
  await section('mode isolation', verifyModeIsolation);
  await section('export', verifyCsvExport);
  await section('guards', verifyGuards);
  await section('themes and viewports', verifyThemesAndViewports);
} finally {
  await browser.close();
  await fetch(`${STUB}/__stub/payments-failure?status=0`).catch(() => undefined);
  await fetch(`${STUB}/__stub/access-ttl?seconds=`).catch(() => undefined);
}

for (const { name, ok, detail } of checks) {
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}
if (failures.length > 0) {
  console.error(`\n${failures.length} payments check(s) failed:`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`\nAll ${checks.length} payments checks passed.`);

// ──────────────────────────────────────────────────────────────────────────────────────

/** A fresh context, signed in, on the payments route with rows rendered. */
async function onPayments(options = {}, query = '') {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, ...options });
  const page = await context.newPage();
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  await page.fill('#field-email', CREDENTIALS.email);
  await page.fill('#field-password', CREDENTIALS.password);
  await page.getByRole('button', { name: /^sign in$/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15_000 });
  await page.goto(`${BASE}${ROUTE}${query}`, { waitUntil: 'networkidle' });
  await page.getByRole('heading', { name: 'Payments' }).waitFor({ timeout: 10_000 });
  return { context, page };
}

/** Waits until the skeleton is gone, so a count is a count of data rows. */
async function settled(page) {
  await waitFor(async () => (await page.getByTestId('payments-skeleton').count()) === 0);
}

async function verifyInstrument() {
  const { context, page } = await onPayments();
  const state = await page.evaluate(() => document.visibilityState);
  check('the browser is visible and compositing', state === 'visible', state);
  check('the payments route renders', page.url().includes(ROUTE), page.url());
  check(
    'and is reachable from the sidebar rather than only by URL',
    (await page
      .getByRole('navigation', { name: 'Main' })
      .getByRole('link', { name: 'Payments' })
      .count()) === 1,
  );
  await context.close();
}

async function verifyFirstPage() {
  const { context, page } = await onPayments();
  await settled(page);

  const rows = await rowCount(page);
  check('the first page arrives from the platform', rows === 25, `${rows} rows`);
  check('the row count is reported', /25\+? payments/.test(await mainText(page)));

  const body = await mainText(page);
  check('statuses are rendered as words, not enum constants', /Captured/.test(body));
  check('the two-word refund status reads properly', /Partly refunded/.test(body));
  check(
    'amounts are formatted as money',
    /[$€]\s?[\d,]+\.\d{2}/.test(body),
    body.match(/[$€]\s?[\d,]+\.\d{2}/)?.[0],
  );
  check('metadata is previewed', /order_id=/.test(body));

  // Every row's id is distinct: a cursor that repeats a row is the classic pagination defect.
  const ids = await idCells(page);
  check(
    'no row is duplicated',
    new Set(ids).size === ids.length,
    `${new Set(ids).size}/${ids.length}`,
  );
  await context.close();
}

/**
 * Cursor pagination.
 *
 * The properties worth asserting are that the second page is *different* rows, that they are
 * appended rather than replacing the first page, and that the end of the ledger is reached and
 * announced rather than offering a button that fetches nothing.
 */
async function verifyCursorPagination() {
  const { context, page } = await onPayments();
  await settled(page);

  const firstPage = await idCells(page);
  await page.getByRole('button', { name: /load more/i }).click();
  await waitFor(async () => (await rowCount(page)) > firstPage.length);

  const twoPages = await idCells(page);
  check(
    'a second page is appended, not swapped in',
    twoPages.length === 50,
    `${twoPages.length} rows`,
  );
  check('the first page is still there', twoPages.slice(0, 25).join() === firstPage.join());
  check('the second page holds different rows', new Set(twoPages).size === twoPages.length);

  // The stub ledger is 60 rows: 25 + 25 + 10, so the third click exhausts it.
  await page.getByRole('button', { name: /load more/i }).click();
  await waitFor(async () => (await rowCount(page)) === 60);
  check('the last page is short and complete', (await rowCount(page)) === 60);
  check(
    'the end is announced rather than offering another page',
    await waitFor(async () => /end of results/i.test(await mainText(page))),
  );
  check(
    'and the load-more button is gone',
    (await page.getByRole('button', { name: /load more/i }).count()) === 0,
  );
  await context.close();
}

/**
 * Filtering, asserted against the platform's answer rather than the DOM's.
 *
 * A filter that is applied in the browser looks identical to one applied by the platform until the
 * result set is larger than a page — so the check is that the *count* changes, and that every
 * remaining row carries the filtered value.
 */
async function verifyFiltering() {
  const { context, page } = await onPayments();
  await settled(page);
  const before = await rowCount(page);

  await page.getByRole('button', { name: /^filter/i }).click();
  await page.getByRole('menuitemcheckbox', { name: 'Captured' }).click();
  await page.keyboard.press('Escape');

  check(
    'the filter reaches the URL',
    await waitFor(async () => page.url().includes('status=captured')),
    page.url(),
  );
  await settled(page);

  const after = await rowCount(page);
  check('the list narrows', after < before, `${before} → ${after}`);
  check(
    'every remaining row has the filtered status',
    (await page.locator('table tbody tr').allInnerTexts()).every((row) => /Captured/.test(row)),
  );
  check('the applied filter is shown as a chip', /Status: Captured/.test(await mainText(page)));

  // A currency filter on top of it: two parameters, both server-side.
  await page.getByRole('button', { name: /^filter/i }).click();
  await page.getByRole('menuitemcheckbox', { name: 'EUR' }).click();
  await page.keyboard.press('Escape');
  check(
    'a second filter is added rather than replacing the first',
    await waitFor(
      async () => page.url().includes('status=captured') && page.url().includes('currency=EUR'),
    ),
    page.url(),
  );
  await settled(page);
  check(
    'and both are applied to every row',
    (await page.locator('table tbody tr').allInnerTexts()).every((row) => /Captured/.test(row)) &&
      (await mainText(page)).includes('€'),
  );

  // Removing one chip must leave the other applied.
  await page
    .getByRole('button', { name: /remove filter/i })
    .first()
    .click();
  check(
    'removing a chip removes exactly that filter',
    await waitFor(
      async () => !page.url().includes('status=captured') && page.url().includes('currency=EUR'),
    ),
    page.url(),
  );

  await page.getByRole('button', { name: /clear all/i }).click();
  check(
    'clear all empties the query string',
    await waitFor(async () => new URL(page.url()).search === ''),
    page.url(),
  );
  await settled(page);
  check(
    'and the full list is back',
    await waitFor(async () => (await rowCount(page)) === before),
    `${await rowCount(page)} of ${before}`,
  );
  await context.close();
}

/**
 * The view is its URL.
 *
 * §6.2 asks for saved views and the platform has nowhere to save one, so a view is a link. This is
 * the check that makes that claim true: a URL typed into a fresh browser produces the same filters,
 * the same chips and the same rows.
 */
async function verifyTheUrlIsTheView() {
  const query = '?status=failed&currency=USD&amount_min=2000';
  const { context, page } = await onPayments({}, query);
  await settled(page);

  check('a shared link restores the filters', /Status: Failed/.test(await mainText(page)));
  check('and the currency', /Currency: USD/.test(await mainText(page)));
  check('and the amount range', /Amount: 2000/.test(await mainText(page)));
  check(
    'and the rows it describes',
    (await page.locator('table tbody tr').allInnerTexts()).every((row) => /Failed/.test(row)),
  );

  // The one thing a shared link must never carry.
  check('the link names no merchant', !page.url().includes('merchant'));
  check('and no mode', !/[?&]mode=/.test(page.url()), page.url());

  /*
   * Metadata — the filter that needed the deepObject spelling to work at all.
   *
   * `order_id` is unique per row in the stub ledger, so a match on one value must produce exactly
   * one row. A filter that was silently dropped would produce a full page of 25, which is why this
   * asserts the count rather than only that the visible rows match: with `channel=mobile` the
   * first page is full either way, and the check would have passed while filtering nothing.
   */
  await page.goto(`${BASE}${ROUTE}?metadata_key=order_id&metadata_value=ord_7`, {
    waitUntil: 'networkidle',
  });
  await settled(page);
  check(
    'a metadata filter reaches the platform and narrows to the one match',
    await waitFor(async () => (await rowCount(page)) === 1),
    `${await rowCount(page)} rows`,
  );
  check('and that row is the one asked for', /order_id=ord_7/.test(await mainText(page)));
  await context.close();
}

async function verifyNoMatches() {
  const { context, page } = await onPayments({}, '?amount_min=99999999');
  await settled(page);

  const body = await mainText(page);
  check(
    'an impossible filter says "no matches", not "no payments"',
    /no payments match/i.test(body),
  );
  check(
    'and offers to clear the filters',
    (await page.getByRole('button', { name: /clear filters/i }).count()) === 1,
  );
  check(
    'the table is not rendered empty alongside it',
    (await page.locator('table').count()) === 0,
  );

  await page.getByRole('button', { name: /clear filters/i }).click();
  await settled(page);
  check('clearing brings the rows back', await waitFor(async () => (await rowCount(page)) === 25));
  await context.close();
}

/**
 * A failed read.
 *
 * §6.6 requires the platform's request id on every surfaced error — it is what turns "it broke"
 * into a support conversation — so the check is that the id is on screen, not merely that an
 * error is.
 */
async function verifyErrorHandling() {
  await fetch(`${STUB}/__stub/payments-failure?status=503`);
  const { context, page } = await onPayments();

  check(
    'a failed read is reported rather than shown as an empty list',
    await waitFor(async () => /could not load payments/i.test(await mainText(page))),
  );
  const body = await mainText(page);
  check('the platform’s code is quoted', /stub_forced_failure/.test(body));
  check('and its request id', /req_stubfail0001/.test(body), body.match(/req_\w+/)?.[0]);
  check('an empty state is not shown instead', !/no test payments yet/i.test(body));

  await fetch(`${STUB}/__stub/payments-failure?status=0`);
  await page.getByRole('button', { name: /try again/i }).click();
  await settled(page);
  check(
    'retrying recovers without a reload',
    await waitFor(async () => (await rowCount(page)) === 25),
  );
  await context.close();
}

/**
 * Mode isolation, in both directions.
 *
 * The stub holds different ledgers per mode, as the platform does — mode is bound into the
 * gateway's signed internal context (M23.0). The property is that no row from one mode is ever
 * visible under the other, including from the query cache after a switch.
 */
async function verifyModeIsolation() {
  const { context, page } = await onPayments();
  await settled(page);

  const testIds = await idCells(page);
  check(
    'test mode shows the test ledger',
    testIds.every((id) => id.startsWith('11111111')),
    testIds[0],
  );
  check('the test ledger is the long one', (await rowCount(page)) === 25);

  await page.getByRole('button', { name: /data mode/i }).click();
  await page.getByRole('menuitem', { name: /live mode/i }).click();
  await page.waitForURL((url) => url.pathname === ROUTE, { timeout: 15_000 });
  await settled(page);

  const liveIds = await idCells(page);
  check(
    'live mode shows the live ledger',
    liveIds.every((id) => id.startsWith('ffffffff')),
    liveIds[0],
  );
  check(
    'which is a different length',
    (await rowCount(page)) === 7,
    `${await rowCount(page)} rows`,
  );
  check(
    'no test-mode row survives the switch',
    liveIds.every((id) => !testIds.includes(id)),
  );
  check(
    'and no test id is anywhere in the live document',
    !(await page.content()).includes('11111111-aaaa'),
  );

  // Back again: the cache must not serve live rows under test.
  await page.getByRole('button', { name: /data mode/i }).click();
  await page.getByRole('menuitem', { name: /test mode/i }).click();
  await page.waitForURL((url) => url.pathname === ROUTE, { timeout: 15_000 });
  await settled(page);

  const backIds = await idCells(page);
  check(
    'switching back restores the test ledger',
    backIds.every((id) => id.startsWith('11111111')),
  );
  check(
    'and no live id is anywhere in the test document',
    !(await page.content()).includes('ffffffff-aaaa'),
  );
  await context.close();
}

async function verifyCsvExport() {
  const { context, page } = await onPayments();
  await settled(page);

  const download = page.waitForEvent('download', { timeout: 15_000 });
  await page.getByRole('button', { name: /export 25 rows/i }).click();
  const file = await download;

  check(
    'the export names itself, its mode and its date',
    /paymentflow-payments-test-.*\.csv/.test(file.suggestedFilename()),
    file.suggestedFilename(),
  );

  const stream = await file.createReadStream();
  const csv = await new Promise((resolve, reject) => {
    let text = '';
    stream.on('data', (chunk) => (text += chunk));
    stream.on('end', () => resolve(text));
    stream.on('error', reject);
  });

  const lines = csv.trim().split('\r\n');
  check('it has a header and one row per payment', lines.length === 26, `${lines.length} lines`);
  check('the header names minor units, so nobody divides twice', /amount_minor/.test(lines[0]));
  check('the rows are the rows on screen', lines[1].includes('11111111-aaaa'));
  check('and no secret or token rode along', !/eyJ|sk_test|sk_live/.test(csv));
  await context.close();
}

async function verifyGuards() {
  const context = await browser.newContext();
  const page = await context.newPage();

  await page.goto(`${BASE}${ROUTE}`, { waitUntil: 'networkidle' });
  check(
    'the route is closed to a browser with no session',
    page.url().includes('/login'),
    page.url(),
  );
  check('and remembers where it was going', page.url().includes('payments'), page.url());

  // The read route is the only way the browser reaches the platform, and it names its own rules.
  const denied = await page.evaluate(async () => {
    const response = await fetch('/api/platform/listPayments?mode=live');
    return { status: response.status, body: await response.text() };
  });
  check(
    'the read route refuses a mode the client tried to choose',
    denied.status === 400 || denied.status === 401,
    `HTTP ${denied.status}`,
  );
  await context.close();

  const signedIn = await onPayments();
  await settled(signedIn.page);

  const probe = await signedIn.page.evaluate(async () => {
    const attempt = async (url) => (await fetch(url)).status;
    return {
      mode: await attempt('/api/platform/listPayments?mode=live'),
      merchant: await attempt(
        '/api/platform/listPayments?merchantId=00000000-0000-4000-8000-000000000000',
      ),
      mutation: await attempt('/api/platform/capturePayment'),
      ok: await attempt('/api/platform/listPayments?limit=1'),
    };
  });
  check('a session cannot ask for another mode', probe.mode === 400, `HTTP ${probe.mode}`);
  check('nor name a merchant', probe.merchant === 400, `HTTP ${probe.merchant}`);
  check(
    'nor reach a mutation through the read route',
    probe.mutation === 404,
    `HTTP ${probe.mutation}`,
  );
  check('while a documented read still works', probe.ok === 200, `HTTP ${probe.ok}`);

  const storage = await signedIn.page.evaluate(() => ({
    cookie: document.cookie,
    local: JSON.stringify(localStorage),
    session: JSON.stringify(sessionStorage),
  }));
  check('no session cookie is readable by script', !storage.cookie.includes('pf_session'));
  check(
    'no payment data is persisted to localStorage',
    !/payment|amountMinor/i.test(storage.local),
    storage.local.slice(0, 60),
  );
  check('nor to sessionStorage', !/payment|amountMinor/i.test(storage.session));
  check(
    'no JWT appears in the payments HTML',
    !/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\./.test(await signedIn.page.content()),
  );
  await signedIn.context.close();
}

async function verifyThemesAndViewports() {
  for (const scheme of ['dark', 'light']) {
    const { context, page } = await onPayments({ colorScheme: scheme });
    await settled(page);
    const contrast = await page.evaluate(() => {
      const luminance = (color) => {
        const [r, g, b] = color
          .match(/\d+(\.\d+)?/g)
          .slice(0, 3)
          .map(Number);
        const channel = (v) => {
          const s = v / 255;
          return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
        };
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
      };
      const cell = document.querySelector('table tbody tr td');
      const fg = luminance(getComputedStyle(cell).color);
      const bg = luminance(getComputedStyle(document.body).backgroundColor);
      const [hi, lo] = fg > bg ? [fg, bg] : [bg, fg];
      return (hi + 0.05) / (lo + 0.05);
    });
    check(`the ${scheme} theme's rows are legible`, contrast >= 4.5, `${contrast.toFixed(2)}:1`);
    await context.close();
  }

  // Mobile: the table scrolls inside itself, and the page never does.
  const { context, page } = await onPayments({ viewport: { width: 390, height: 844 } });
  await settled(page);
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  check('the page does not scroll horizontally at 390px', overflow <= 1, `${overflow}px`);
  const scrolls = await page.evaluate(() => {
    const box = document.querySelector('table')?.parentElement;
    return box ? box.scrollWidth > box.clientWidth : false;
  });
  check('the wide table scrolls within its own container instead', scrolls);
  check(
    'and the filter control is still reachable',
    await page.getByRole('button', { name: /^filter/i }).isVisible(),
  );
  await context.close();

  // Reduced motion: rows must be present and opaque, not mid-fade forever.
  const reduced = await browser.newContext({
    viewport: { width: 1280, height: 900 },
    reducedMotion: 'reduce',
  });
  const rPage = await reduced.newPage();
  await rPage.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  await rPage.fill('#field-email', CREDENTIALS.email);
  await rPage.fill('#field-password', CREDENTIALS.password);
  await rPage.getByRole('button', { name: /^sign in$/i }).click();
  await rPage.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15_000 });
  await rPage.goto(`${BASE}${ROUTE}`, { waitUntil: 'networkidle' });
  await settled(rPage);

  check('rows render under reduced motion', (await rowCount(rPage)) === 25);
  const opacity = await rPage.evaluate(() => {
    const row = document.querySelector('table tbody tr');
    return Number(getComputedStyle(row).opacity);
  });
  check('and are fully opaque rather than stuck mid-animation', opacity === 1, String(opacity));
  await reduced.close();
}
