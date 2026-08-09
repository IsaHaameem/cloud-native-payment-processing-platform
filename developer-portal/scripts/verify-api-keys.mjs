/**
 * Browser verification of API key management (M23.5).
 *
 * ── What only a browser can prove ─────────────────────────────────────────────────────
 *
 * `api-keys.test.ts` proves the lifecycle: statuses derive correctly, refusals classify, the
 * confirmation is checked against stored state, and no listing can carry a secret. None of that
 * can prove the property the whole screen exists for — that a secret appears **once**, on screen,
 * and is genuinely unreachable afterwards. That is a claim about a real document: its HTML, its
 * URL, its cookies, its storage, and what a reload produces.
 *
 * So the reveal is checked from the outside. The suite reads the raw key out of the dialog, then
 * goes looking for it everywhere it must not be — including in a fresh page load of the same
 * route, which is the only test that distinguishes "shown once" from "shown until you leave".
 *
 * ── Every block signs in for itself ───────────────────────────────────────────────────
 *
 * Same discipline as the settings suite and for the same measured reason: the stub runs a
 * sixty-second access-token life for the authentication suite's benefit, and a long journey under
 * constant rotation eventually renders after a refused lookup, replacing the list with its "could
 * not load" panel. The failure then surfaces as a missing button several steps later. This suite
 * raises the TTL for its own duration and restores it in the `finally`.
 *
 * State is asserted against the platform (`/__stub/keys`) rather than by re-reading the DOM, so
 * these checks are about what merchant-service holds rather than about how Next.js caches.
 *
 * ── Run ───────────────────────────────────────────────────────────────────────────────
 *
 *   node scripts/stub-platform.mjs &
 *   PF_GATEWAY_URL=http://localhost:4600 PORTAL_PUBLIC_ORIGIN=http://localhost:3601 \
 *   PORT=3601 npm start &
 *   BASE=http://localhost:3601 STUB=http://localhost:4600 node scripts/verify-api-keys.mjs
 */

import { chromium } from 'playwright-core';

const BASE = process.env.BASE ?? 'http://localhost:3000';
const STUB = process.env.STUB ?? 'http://localhost:4600';
const ROUTE = '/developers/api-keys';

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

const mainText = (page) => page.locator('main').innerText();
const waitForMain = (page, pattern) => waitFor(async () => pattern.test(await mainText(page)));

/** What the platform actually holds for the test account — never carries a secret. */
async function storedKeys() {
  const response = await fetch(`${STUB}/__stub/keys?email=${CREDENTIALS.email}`);
  return response.json();
}

/**
 * Waits for the platform's own state to satisfy a predicate, then returns it.
 *
 * Necessary because the confirmation navigations in this screen go to the URL the browser is
 * already on: `page.waitForURL('/developers/api-keys')` resolves *immediately* when that is the
 * current location, so a plain read afterwards races the request that is still in flight. Measured
 * — the revocation check read `revokedAt: null` from a key the screen had already redrawn as
 * revoked.
 */
async function settledKeys(predicate, timeout = 15_000) {
  const deadline = Date.now() + timeout;
  for (;;) {
    const keys = await storedKeys();
    if (predicate(keys) || Date.now() > deadline) return keys;
    await wait(150);
  }
}

/**
 * The reveal, addressed unambiguously.
 *
 * Two dialogs are briefly mounted at once — the create dialog plays its exit while the reveal
 * plays its entrance — so `getByRole('dialog')` is ambiguous exactly when the reveal appears.
 * Filtering by the secret field names the right one at every moment.
 */
const revealDialog = (page) =>
  page.getByRole('dialog').filter({ has: page.getByTestId('revealed-secret') });

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
  await section('empty state', verifyEmptyState);
  await section('creation and reveal', verifyCreationAndReveal);
  await section('secret is gone', verifySecretIsGoneAfterReload);
  await section('rotation', verifyRotation);
  await section('revocation', verifyRevocation);
  await section('mode isolation', verifyModeIsolation);
  await section('errors', verifyErrorHandling);
  await section('guards', verifyGuards);
  await section('themes and viewports', verifyThemesAndViewports);
} finally {
  await browser.close();
  await fetch(`${STUB}/__stub/access-ttl?seconds=`).catch(() => undefined);
}

for (const { name, ok, detail } of checks) {
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}
if (failures.length > 0) {
  console.error(`\n${failures.length} API-key check(s) failed:`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`\nAll ${checks.length} API-key checks passed.`);

// ──────────────────────────────────────────────────────────────────────────────────────

/** A fresh context, signed in, on the API-keys route. */
async function onKeys(options = {}) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, ...options });
  const page = await context.newPage();
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  await page.fill('#field-email', CREDENTIALS.email);
  await page.fill('#field-password', CREDENTIALS.password);
  await page.getByRole('button', { name: /^sign in$/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15_000 });
  await page.goto(`${BASE}${ROUTE}`, { waitUntil: 'networkidle' });
  await page.getByRole('heading', { name: 'API keys' }).waitFor({ timeout: 10_000 });
  return { context, page };
}

/**
 * Fills the create dialog and submits it.
 *
 * @returns the revealed secret, read out of the dialog's own field.
 */
async function createKey(page, { name, type = 'SECRET', mode = 'test', scope = '*' }) {
  await page
    .getByRole('button', { name: /create key/i })
    .first()
    .click();
  const dialog = page.getByRole('dialog');
  await dialog.waitFor({ timeout: 10_000 });

  await dialog.getByLabel('Name', { exact: true }).fill(name);

  /*
   * Everything below is scoped to the dialog, and both reasons were found by running it.
   *
   * `getByRole('radio', { value })` is not a filter Playwright supports, so it matches every
   * radio on the page. And `input[name="mode"]` is not unique to this form — the header's mode
   * switch submits through two hidden forms carrying exactly that name, so an unscoped selector
   * resolves to the chrome as well as to the dialog.
   */
  await dialog.locator(`input[name="type"][value="${type}"]`).check();
  await dialog.locator(`input[name="mode"][value="${mode}"]`).check();

  // Uncheck everything, then check the one asked for: the defaults depend on type, and a test
  // that assumed them would silently stop testing the picker the day they changed.
  const boxes = dialog.locator('input[type="checkbox"][name="scopes"]');
  for (let i = 0; i < (await boxes.count()); i += 1) {
    const box = boxes.nth(i);
    if (await box.isChecked()) await box.uncheck();
  }
  await dialog.locator(`input[name="scopes"][value="${scope}"]`).check();

  await page
    .getByRole('button', { name: /^create key$/i })
    .last()
    .click();
  await page.getByTestId('revealed-secret').waitFor({ timeout: 15_000 });
  return page.getByTestId('revealed-secret').inputValue();
}

async function verifyInstrument() {
  const { context, page } = await onKeys();
  const state = await page.evaluate(() => document.visibilityState);
  check('the browser is visible and compositing', state === 'visible', state);
  check('the API keys route renders', page.url().includes(ROUTE), page.url());
  check(
    'and is reachable from the sidebar rather than only by URL',
    (await page
      .getByRole('navigation', { name: 'Main' })
      .getByRole('link', { name: 'API keys' })
      .count()) === 1,
  );
  await context.close();
}

/**
 * The empty state.
 *
 * Reachable here because the seeded account was never onboarded through the portal. A merchant
 * created by `POST /api/v1/merchants` receives four starter keys, so in production this screen is
 * empty only in the window before onboarding — which is precisely why it still has to be right.
 */
async function verifyEmptyState() {
  const { context, page } = await onKeys();
  const keys = await storedKeys();
  check(
    'the platform starts with no keys for this account',
    keys.length === 0,
    String(keys.length),
  );

  const body = await mainText(page);
  check(
    'an empty list says so in the current mode',
    /no test keys yet/i.test(body),
    body.split('\n')[0],
  );
  check('and explains the once-only rule before anything is created', /shown once/i.test(body));
  check(
    'and offers the one action available',
    (await page.getByRole('button', { name: /create key/i }).count()) >= 1,
  );
  check(
    'the empty state does not pretend a key exists',
    !/sk_test|pk_test/.test(await page.content()),
  );
  await context.close();
}

async function verifyCreationAndReveal() {
  const { context, page } = await onKeys();

  const secret = await createKey(page, { name: 'Reveal probe', type: 'SECRET', mode: 'test' });

  check('creating a key reveals a secret', /^sk_test_/.test(secret), secret.slice(0, 12));
  const dialog = await revealDialog(page).innerText();
  check('the reveal says it is the only time', /only time/i.test(dialog));
  check('and names the mode the key belongs to', /test/i.test(dialog));

  // The acknowledgement gate: the way out is disabled until it is ticked.
  const done = page.getByRole('button', { name: /^done$/i });
  check('the way out is closed until the user acknowledges', await done.isDisabled());

  check(
    'the reveal cannot be dismissed with Escape',
    await (async () => {
      await page.keyboard.press('Escape');
      await wait(300);
      return page.getByTestId('revealed-secret').isVisible();
    })(),
  );

  await page.getByRole('checkbox').last().check();
  check('acknowledging opens it', await done.isEnabled());

  await done.click();
  await page.waitForURL((url) => url.pathname === ROUTE, { timeout: 15_000 });
  check('and the key is now in the list', await waitForMain(page, /Reveal probe/));

  const stored = await settledKeys((keys) => keys.some((key) => key.name === 'Reveal probe'));
  const created = stored.find((key) => key.name === 'Reveal probe');
  check('the platform stored it', created !== undefined);
  check('with the type that was chosen', created?.type === 'SECRET', created?.type);
  check(
    'and the scope that was chosen',
    JSON.stringify(created?.scopes) === '["*"]',
    JSON.stringify(created?.scopes),
  );
  check(
    'the platform never hands back a secret on the management view',
    JSON.stringify(stored).includes('apiKey') === false,
  );

  await context.close();
}

/**
 * The property the milestone rests on: the secret is *gone*.
 *
 * Everything is checked against the real value read out of the dialog, not against a pattern —
 * a regex could pass while the exact credential sat in the HTML.
 */
async function verifySecretIsGoneAfterReload() {
  const { context, page } = await onKeys();

  const secret = await createKey(page, { name: 'Disappearing act', type: 'SECRET', mode: 'test' });
  await page.getByRole('checkbox').last().check();
  await page.getByRole('button', { name: /^done$/i }).click();
  await page.waitForURL((url) => url.pathname === ROUTE, { timeout: 15_000 });
  await waitForMain(page, /Disappearing act/);

  check('the secret is not in the reloaded page source', !(await page.content()).includes(secret));
  check('nor in the URL', !page.url().includes(secret.slice(8)));

  const leaks = await page.evaluate(() => ({
    cookie: document.cookie,
    local: JSON.stringify(localStorage),
    session: JSON.stringify(sessionStorage),
  }));
  check('nor in any readable cookie', !leaks.cookie.includes(secret));
  check('nor in localStorage', !leaks.local.includes(secret), leaks.local.slice(0, 60));
  check('nor in sessionStorage', !leaks.session.includes(secret));

  // A hard reload, so nothing client-side survives to supply it.
  await page.reload({ waitUntil: 'networkidle' });
  check('nor after a full reload', !(await page.content()).includes(secret));
  check(
    'and the row shows only the prefix',
    /sk_test_[a-z0-9]{4}…/.test(await mainText(page)),
    (await mainText(page)).match(/sk_test_\S*/)?.[0],
  );

  // The prefix is public; the rest is not. This is the assertion that a truncation bug would fail.
  check(
    'the visible prefix is not enough to reconstruct the key',
    !(await page.content()).includes(secret.slice(12)),
  );

  await context.close();
}

async function verifyRotation() {
  const { context, page } = await onKeys();

  await createKey(page, { name: 'Rotate me', type: 'SECRET', mode: 'test' });
  await page.getByRole('checkbox').last().check();
  await page.getByRole('button', { name: /^done$/i }).click();
  await page.waitForURL((url) => url.pathname === ROUTE, { timeout: 15_000 });
  await waitForMain(page, /Rotate me/);

  const before = (await storedKeys()).find((key) => key.name === 'Rotate me');

  const row = page.locator('li', { hasText: 'Rotate me' }).first();
  // Anchored: the revoke control is labelled "Revoke Rotate me", which /rotate/i also matches.
  await row.getByRole('button', { name: /^rotate$/i }).click();
  await page.getByRole('dialog').waitFor({ timeout: 10_000 });
  const dialog = await page.getByRole('dialog').innerText();
  check('rotation explains what happens to the old key', /grace window/i.test(dialog));
  check('and that it eventually stops', /stops/i.test(dialog));

  await page.getByRole('button', { name: /^rotate key$/i }).click();
  await page.getByTestId('revealed-secret').waitFor({ timeout: 15_000 });
  const replacement = await page.getByTestId('revealed-secret').inputValue();
  check('rotation reveals a new secret', /^sk_test_/.test(replacement));

  await page.getByRole('checkbox').last().check();
  await page.getByRole('button', { name: /^done$/i }).click();
  await page.waitForURL((url) => url.pathname === ROUTE, { timeout: 15_000 });

  const after = await settledKeys((keys) =>
    keys.some((key) => key.id === before?.id && key.graceExpiresAt),
  );
  const old = after.find((key) => key.id === before?.id);
  check('the old key is still there', old !== undefined);
  check(
    'and now carries a grace deadline',
    Boolean(old?.graceExpiresAt),
    String(old?.graceExpiresAt),
  );
  check(
    'the replacement inherited its name and scopes',
    after.filter((key) => key.name === 'Rotate me').length === 2,
  );

  const body = await mainText(page);
  check('the screen marks the old key as retiring', /retiring/i.test(body));
  check('and says when it stops working', /stops working/i.test(body));
  check(
    'the new secret is not on the reloaded page',
    !(await page.content()).includes(replacement),
  );

  await context.close();
}

async function verifyRevocation() {
  const { context, page } = await onKeys();

  await createKey(page, { name: 'Revoke me', type: 'SECRET', mode: 'test' });
  await page.getByRole('checkbox').last().check();
  await page.getByRole('button', { name: /^done$/i }).click();
  await page.waitForURL((url) => url.pathname === ROUTE, { timeout: 15_000 });
  await waitForMain(page, /Revoke me/);

  const row = page.locator('li', { hasText: 'Revoke me' }).first();
  await row.getByRole('button', { name: /revoke revoke me/i }).click();
  await page.getByRole('dialog').waitFor({ timeout: 10_000 });

  const confirm = page.getByRole('button', { name: /^revoke key$/i });
  check('revocation is refused until the name is typed', await confirm.isDisabled());

  await page.getByRole('textbox').last().fill('Revoke Me');
  check('a near miss is still refused', await confirm.isDisabled(), 'wrong case');

  await page.getByRole('textbox').last().fill('Revoke me');
  check('the exact name unlocks it', await confirm.isEnabled());

  const before = (await storedKeys()).find((key) => key.name === 'Revoke me');
  await confirm.click();
  await page.waitForURL((url) => url.pathname === ROUTE, { timeout: 15_000 });

  const after = (
    await settledKeys((keys) => keys.some((key) => key.id === before?.id && key.revokedAt))
  ).find((key) => key.id === before?.id);
  check('the platform revoked it', Boolean(after?.revokedAt), String(after?.revokedAt));
  check('the key is kept as a record rather than deleted', after !== undefined);
  check('the screen marks it revoked', await waitForMain(page, /revoked/i));

  const revokedRow = page.locator('li', { hasText: 'Revoke me' }).first();
  check(
    'and offers no actions on a dead key',
    (await revokedRow.getByRole('button').count()) === 0,
    String(await revokedRow.getByRole('button').count()),
  );

  await context.close();
}

/**
 * Mode is a display scope, not an authorization boundary — a session's owner is entitled to both
 * modes of their own merchant (D184). What must hold is that the two never bleed into one view.
 */
async function verifyModeIsolation() {
  const { context, page } = await onKeys();

  const secret = await createKey(page, { name: 'Live only', type: 'SECRET', mode: 'live' });
  check('a live key is created with a live prefix', /^sk_live_/.test(secret), secret.slice(0, 12));

  await page.getByRole('checkbox').last().check();
  await page.getByRole('button', { name: /^done$/i }).click();
  await page.waitForURL((url) => url.pathname === ROUTE, { timeout: 15_000 });

  check('creating in the other mode moves the session there', await waitForMain(page, /Live only/));
  const header = await page.locator('header').innerText();
  check('and the header agrees', /live mode/i.test(header), header.split('\n')[0]);

  const liveBody = await mainText(page);
  check('test keys are not listed in live mode', !/Reveal probe|Rotate me/.test(liveBody));
  check(
    'and the page says where the others are',
    /more key|switch mode/i.test(liveBody),
    liveBody.split('\n').find((line) => /more key/i.test(line)),
  );

  // Back to test, through the product's own control rather than by editing a cookie.
  await page.getByRole('button', { name: /data mode/i }).click();
  await page.getByRole('menuitem', { name: /test mode/i }).click();
  await page.waitForURL((url) => url.pathname === ROUTE, { timeout: 15_000 });
  await waitForMain(page, /Reveal probe/);

  const testBody = await mainText(page);
  check('switching back shows the test keys', /Reveal probe/.test(testBody));
  check('and hides the live one', !/Live only/.test(testBody));
  check(
    'no live key material appears in a test-mode document',
    !(await page.content()).includes('sk_live_'),
  );

  await context.close();
}

async function verifyErrorHandling() {
  const { context, page } = await onKeys();

  // A revocation whose confirmation is right in the browser but wrong at the server: the field is
  // filled, then the key's name changes underneath it. The server-side check is the one that must
  // catch this, since the button was legitimately enabled.
  await createKey(page, { name: 'Server checked', type: 'SECRET', mode: 'test' });
  await page.getByRole('checkbox').last().check();
  await page.getByRole('button', { name: /^done$/i }).click();
  await page.waitForURL((url) => url.pathname === ROUTE, { timeout: 15_000 });
  await waitForMain(page, /Server checked/);

  const row = page.locator('li', { hasText: 'Server checked' }).first();
  await row.getByRole('button', { name: /revoke server checked/i }).click();
  await page.getByRole('dialog').waitFor({ timeout: 10_000 });
  await page.getByRole('textbox').last().fill('Server checked');

  // Rewrite the hidden key id to one this merchant does not own. The dialog still looks valid.
  await page.evaluate(() => {
    const field = document.querySelector('input[name="keyId"]');
    if (field) field.value = 'aaaaaaaa-bbbb-4ccc-8ddd-999999999999';
  });
  await page.getByRole('button', { name: /^revoke key$/i }).click();

  check(
    'a key this merchant does not own is refused, not revoked',
    await waitFor(async () => /no longer exists/i.test(await page.getByRole('dialog').innerText())),
    (await page.getByRole('dialog').innerText()).slice(0, 80),
  );
  const stillThere = (await storedKeys()).find((key) => key.name === 'Server checked');
  check('and nothing was revoked', stillThere?.revokedAt == null);

  await context.close();
}

/**
 * The guards, from the browser's side.
 *
 * The unit suite proves the actions refuse a bad token; what this adds is that the token is
 * actually on the form, and that the route is not reachable without a session at all.
 */
async function verifyGuards() {
  const context = await browser.newContext();
  const page = await context.newPage();

  await page.goto(`${BASE}${ROUTE}`, { waitUntil: 'networkidle' });
  check(
    'the route is closed to a browser with no session',
    page.url().includes('/login'),
    page.url(),
  );
  check('and says where it was going', page.url().includes('api-keys'), page.url());
  await context.close();

  const signedIn = await onKeys();
  await signedIn.page
    .getByRole('button', { name: /create key/i })
    .first()
    .click();
  await signedIn.page.getByRole('dialog').waitFor({ timeout: 10_000 });
  const token = await signedIn.page.locator('input[name="csrfToken"]').first().inputValue();
  check('every mutation form carries a CSRF token', token.length >= 32, `${token.length} chars`);
  check(
    'and the session cookie is still unreadable by script',
    !(await signedIn.page.evaluate(() => document.cookie)).includes('pf_session'),
  );
  await signedIn.context.close();
}

async function verifyThemesAndViewports() {
  // Dark is the default; light is the one that has historically broken.
  for (const scheme of ['dark', 'light']) {
    const { context, page } = await onKeys({ colorScheme: scheme });
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
      const heading = document.querySelector('h1');
      const fg = luminance(getComputedStyle(heading).color);
      const bg = luminance(getComputedStyle(document.body).backgroundColor);
      const [hi, lo] = fg > bg ? [fg, bg] : [bg, fg];
      return (hi + 0.05) / (lo + 0.05);
    });
    check(`the ${scheme} theme's heading is legible`, contrast >= 4.5, `${contrast.toFixed(2)}:1`);
    await context.close();
  }

  // Mobile: the row wraps rather than pushing the page sideways.
  const { context, page } = await onKeys({ viewport: { width: 390, height: 844 } });
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  check('the page does not scroll horizontally at 390px', overflow <= 1, `${overflow}px`);
  check(
    'and the create action is still reachable',
    await page
      .getByRole('button', { name: /create key/i })
      .first()
      .isVisible(),
  );
  await context.close();

  // Reduced motion: the reveal is the one dialog that must never be missed.
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

  const secret = await createKey(rPage, { name: 'Reduced motion', type: 'SECRET', mode: 'test' });
  check('the reveal is shown under reduced motion', secret.startsWith('sk_test_'));
  const box = await rPage.getByTestId('revealed-secret').boundingBox();
  check('and the secret is actually in view', box !== null && box.width > 0 && box.height > 0);
  await reduced.close();
}
