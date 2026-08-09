/**
 * Browser verification of merchant settings (M23.4).
 *
 * ── What only a browser can prove ─────────────────────────────────────────────────────
 *
 * `settings.test.ts` proves the actions validate, classify and never name a merchant. What it
 * cannot prove is the loop a person performs: open the screen, see the *stored* values, change
 * one, save it, and find the change reflected — including in the shell's own header, which reads
 * the business name through a different call than the form does.
 *
 * ── Every block signs in for itself, and keeps its session short ──────────────────────
 *
 * The first draft ran one long journey per block: sign in, then several saves, polls and
 * navigations on one page. It was intermittently wrong, and the reason is worth recording because
 * it is a property of the harness rather than of the portal — the stub runs with a sixty-second
 * access-token life for the authentication suite's benefit, so a block that spends long enough
 * between steps eventually renders after a lookup the platform refused, and the form is replaced
 * by its "could not load" panel. The button then does not exist, and the failure arrives as a
 * selector timeout thirty seconds later pointing at the wrong thing.
 *
 * So each block does one thing with a fresh session. Persistence is asserted against the
 * platform's own state (`/__stub/merchant`) rather than by re-reading the DOM, which keeps these
 * checks about storage rather than about how Next.js caches a dynamic route.
 *
 * ── Run ───────────────────────────────────────────────────────────────────────────────
 *
 *   node scripts/stub-platform.mjs &
 *   PF_GATEWAY_URL=http://localhost:4600 PORTAL_PUBLIC_ORIGIN=http://localhost:3601 \
 *   PORT=3601 npm start &
 *   BASE=http://localhost:3601 STUB=http://localhost:4600 node scripts/verify-settings.mjs
 */

import { chromium } from 'playwright-core';

const BASE = process.env.BASE ?? 'http://localhost:3000';
const STUB = process.env.STUB ?? 'http://localhost:4600';

const CREDENTIALS = { email: 'ada@example.com', password: 'correct horse battery staple' };

const checks = [];
const failures = [];

function check(name, condition, detail) {
  checks.push({ name, ok: Boolean(condition), detail });
  if (!condition) failures.push(`${name}${detail ? ` — ${detail}` : ''}`);
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Waits for an outcome rather than for a duration.
 *
 * The ceiling is generous on purpose. A check that is going to fail still fails — the value
 * never appears — so the only thing a tight limit buys is the occasional false negative on the
 * tenth browser context of a loaded machine, which is a worse trade than waiting.
 */
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

/** What the platform actually holds for the test account. */
async function storedMerchant() {
  const response = await fetch(`${STUB}/__stub/merchant?email=${CREDENTIALS.email}`);
  return response.json();
}

const browser = await chromium.launch({ channel: 'chrome', headless: true });

/** Runs one block, turning an exception into a reported failure rather than hiding the rest. */
async function section(name, fn) {
  try {
    await fn();
  } catch (error) {
    check(`${name} completed`, false, String(error).slice(0, 120));
  }
}

try {
  await fetch(`${STUB}/__stub/reset`);
  /*
   * A normal token life for this suite.
   *
   * The stub process runs at sixty seconds so the authentication suite always exercises a
   * refresh. Under that setting every request in this suite also refreshes, and a page can render
   * after a refused lookup — which shows up as a missing button several steps later rather than
   * as the token problem it is. Restored in the  below so the next suite gets what it
   * expects.
   */
  await fetch(`${STUB}/__stub/access-ttl?seconds=900`);
  await section('instrument', verifyInstrument);
  await section('stored values', verifyItLoadsStoredValues);
  await section('business round trip', verifyBusinessProfileRoundTrip);
  await section('callback rejected', verifyCallbackRejectsPlainHttp);
  await section('callback stored', verifyCallbackIsStored);
  await section('callback cleared', verifyCallbackCanBeCleared);
  await section('validation', verifyValidation);
  await section('guards', verifyGuards);
  await section('blocked sections', verifyBlockedSectionsAreNamed);
  await section('themes and viewports', verifyThemesAndViewports);
} finally {
  await browser.close();
  await fetch(`${STUB}/__stub/access-ttl?seconds=`).catch(() => undefined);
}

for (const { name, ok, detail } of checks) {
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}
if (failures.length > 0) {
  console.error(`\n${failures.length} settings check(s) failed:`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`\nAll ${checks.length} settings checks passed.`);

// ──────────────────────────────────────────────────────────────────────────────────────

/** A fresh context, signed in, already on settings with the forms rendered. */
async function onSettings(options = {}) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, ...options });
  const page = await context.newPage();
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  await page.fill('#field-email', CREDENTIALS.email);
  await page.fill('#field-password', CREDENTIALS.password);
  await page.getByRole('button', { name: /^sign in$/i }).click();
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15_000 });
  await page.goto(`${BASE}/settings`, { waitUntil: 'networkidle' });
  // The forms must be present: if a lookup failed the page renders its "could not load" panel,
  // and every assertion after this would be about the wrong thing.
  await page.locator('#field-businessName').waitFor({ timeout: 10_000 });
  return { context, page };
}

async function verifyInstrument() {
  const { context, page } = await onSettings();
  const state = await page.evaluate(() => document.visibilityState);
  check('the browser is visible and compositing', state === 'visible', state);
  check('the settings route renders', page.url().includes('/settings'), page.url());
  check(
    'and is reachable from the sidebar, not just by URL',
    (await page
      .getByRole('navigation', { name: 'Main' })
      .getByRole('link', { name: 'Settings' })
      .count()) === 1,
  );
  await context.close();
}

async function verifyItLoadsStoredValues() {
  const { context, page } = await onSettings();
  const stored = await storedMerchant();

  check(
    'the business name is loaded from the platform',
    (await page.locator('#field-businessName').inputValue()) === stored.businessName,
    await page.locator('#field-businessName').inputValue(),
  );
  check(
    'the contact address is loaded too',
    (await page.locator('#field-contactEmail').inputValue()) === stored.contactEmail,
  );
  check(
    'an unset callback URL shows as empty rather than as "null"',
    (await page.locator('#field-webhookUrl').inputValue()) === '',
  );

  const body = await mainText(page);
  check('the account section shows the signed-in address', body.includes(CREDENTIALS.email));
  check('and whether the address is verified', /unverified|verified/i.test(body));

  // Nothing is saveable until something changes: `PATCH /me` replaces both fields, so an
  // accidental submit of an untouched form is a write.
  check(
    'the save button starts disabled',
    await page.getByRole('button', { name: /save changes/i }).isDisabled(),
  );
  await context.close();
}

async function verifyBusinessProfileRoundTrip() {
  const { context, page } = await onSettings();

  const renamed = `Ada Lovelace Ltd ${Date.now()}`;
  await page.fill('#field-businessName', renamed);
  check(
    'editing enables the save button',
    !(await page.getByRole('button', { name: /save changes/i }).isDisabled()),
  );

  await page.getByRole('button', { name: /save changes/i }).click();
  check('the save is confirmed on screen', await waitForMain(page, /saved/i));
  check(
    'and the platform stored it',
    await waitFor(async () => (await storedMerchant()).businessName === renamed),
    (await storedMerchant()).businessName,
  );

  // The shell reads the business name through its own call, so a save that only updated the form
  // would leave two different names on one screen.
  check(
    'the shell header shows the new name',
    await waitFor(async () => (await page.locator('header').innerText()).includes(renamed)),
    (await page.locator('header').innerText()).slice(0, 60),
  );
  await context.close();
}

async function verifyCallbackRejectsPlainHttp() {
  const { context, page } = await onSettings();

  // merchant-service enforces `^https://.+`; refusing it here first means the user is told why
  // rather than shown a bean-validation message.
  await page.fill('#field-webhookUrl', 'http://insecure.example/hook');
  await page.getByRole('button', { name: /save callback url/i }).click();
  check('a non-https callback URL is refused with a reason', await waitForMain(page, /https/i));
  check('and nothing is stored', ((await storedMerchant()).webhookUrl ?? null) === null);
  await context.close();
}

async function verifyCallbackIsStored() {
  const { context, page } = await onSettings();

  const callback = `https://api.example.com/paymentflow/${Date.now()}`;
  await page.fill('#field-webhookUrl', callback);
  await page.getByRole('button', { name: /save callback url/i }).click();

  check('the callback save is confirmed', await waitForMain(page, /saved/i));
  // Asked of the platform rather than of a re-rendered page: this is a persistence check, and
  // reading it back off the DOM would also be measuring how Next.js caches a dynamic route.
  check(
    'the callback URL is stored by the platform',
    await waitFor(async () => (await storedMerchant()).webhookUrl === callback),
    String((await storedMerchant()).webhookUrl),
  );
  await context.close();

  // And a fresh visit shows it, which is the half a user sees.
  const revisit = await onSettings();
  check(
    'and a fresh visit shows it',
    (await revisit.page.locator('#field-webhookUrl').inputValue()) === callback,
    await revisit.page.locator('#field-webhookUrl').inputValue(),
  );
  await revisit.context.close();
}

async function verifyCallbackCanBeCleared() {
  const { context, page } = await onSettings();

  // Blank clears it — the contract documents that, so an empty field is an instruction rather
  // than a validation failure.
  await page.fill('#field-webhookUrl', '');
  await page.getByRole('button', { name: /save callback url/i }).click();
  check('clearing is confirmed', await waitForMain(page, /saved/i));
  check(
    'and the platform holds nothing',
    await waitFor(async () => ((await storedMerchant()).webhookUrl ?? null) === null),
    String((await storedMerchant()).webhookUrl),
  );
  await context.close();
}

async function verifyValidation() {
  const { context, page } = await onSettings();

  await page.fill('#field-businessName', '   ');
  await page.getByRole('button', { name: /save changes/i }).click();
  check(
    'a blank business name is refused with a message',
    await waitForMain(page, /enter the name/i),
  );

  /*
   * A malformed address is stopped by the browser before a request is made — the field is
   * `type="email"` and `required`, so `checkValidity()` fails and the form never submits. That is
   * the better outcome, so this asserts it rather than the server message the first draft looked
   * for and never saw. The action validates independently; `settings.test.ts` covers that path,
   * because a check enforced only by the browser is not enforced.
   */
  await page.fill('#field-businessName', 'Ada Lovelace Ltd');
  await page.fill('#field-contactEmail', 'not-an-address');
  const valid = await page.locator('#field-contactEmail').evaluate((el) => el.checkValidity());
  check('a malformed contact address is refused by the field itself', valid === false);
  await context.close();
}

async function verifyGuards() {
  const context = await browser.newContext();
  const page = await context.newPage();

  await page.goto(`${BASE}/settings`, { waitUntil: 'networkidle' });
  check('settings is not public', page.url().includes('/login'), page.url());
  check(
    'and the redirect remembers where they were going',
    decodeURIComponent(page.url()).includes('next=/settings'),
    page.url(),
  );
  await context.close();
}

async function verifyBlockedSectionsAreNamed() {
  const { context, page } = await onSettings();
  const body = await mainText(page);

  // Both are blocked by the platform rather than deferred by preference, and the page says so —
  // a screen that silently lacks a section a user expects reads as unfinished.
  check('the page names team management as not yet possible', /team/i.test(body));
  check('and the API version pin', /api version/i.test(body));

  const href = await page.getByRole('link', { name: /change password/i }).getAttribute('href');
  check(
    'changing a password links to the real recovery flow',
    href === '/forgot-password',
    String(href),
  );
  await context.close();
}

async function verifyThemesAndViewports() {
  for (const theme of ['dark', 'light']) {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    await context.addInitScript((t) => window.localStorage.setItem('theme', t), theme);
    const page = await context.newPage();
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
    await page.fill('#field-email', CREDENTIALS.email);
    await page.fill('#field-password', CREDENTIALS.password);
    await page.getByRole('button', { name: /^sign in$/i }).click();
    await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15_000 });
    await page.goto(`${BASE}/settings`, { waitUntil: 'networkidle' });
    await page.locator('#field-businessName').waitFor({ timeout: 10_000 });

    const background = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    const label = await page.evaluate(() => {
      const el = document.querySelector('label[for="field-businessName"]');
      return el ? getComputedStyle(el).color : '';
    });
    check(`the ${theme} theme paints settings`, background !== 'rgba(0, 0, 0, 0)', background);
    check(
      `the ${theme} theme's field labels are legible`,
      contrast(background, label) >= 4.5,
      `${contrast(background, label).toFixed(2)}:1`,
    );
    await context.close();
  }

  const mobile = await onSettings({ viewport: { width: 390, height: 844 } });
  const overflows = await mobile.page.evaluate(
    () => document.documentElement.scrollWidth > window.innerWidth + 1,
  );
  check('settings does not scroll horizontally at 390px', !overflows);
  check(
    'and the fields are still usable',
    await mobile.page.locator('#field-businessName').isVisible(),
  );
  await mobile.context.close();

  // Reduced motion: the confirmation is an opacity fade, which `reducedMotion="user"` keeps —
  // the state change must still be visible when the movement is not.
  const reduced = await onSettings({ reducedMotion: 'reduce' });
  await reduced.page.fill('#field-businessName', `Reduced Motion Ltd ${Date.now()}`);
  await reduced.page.getByRole('button', { name: /save changes/i }).click();
  check(
    'the saved confirmation appears under reduced motion',
    await waitForMain(reduced.page, /saved/i),
  );
  await reduced.context.close();
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
