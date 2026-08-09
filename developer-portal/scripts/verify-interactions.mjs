/**
 * Interaction verification for the portal (M23.1).
 *
 * ── Why this exists ───────────────────────────────────────────────────────────────────
 *
 * `npm run verify` proves the portal compiles, lints, is formatted, and builds. None of that can
 * observe an overlay that opens invisible, a drawer that never slides in, or a dialog that cannot
 * be dismissed — the failures a user hits first.
 *
 * It exists in this specific form because of a measurement failure worth recording. The overlays
 * were investigated for a long time through a browser whose `document.visibilityState` was
 * `hidden`. No `requestAnimationFrame` fires in a hidden tab, Framer Motion's frameloop is driven
 * by `requestAnimationFrame`, and so *no animation ran at all*: entrances sat frozen on their
 * `initial` values, and exits never completed, which left `AnimatePresence` holding subtrees
 * mounted forever. Every symptom pointed at the animation code. The animation code was fine.
 *
 * So the first thing this script asserts is the instrument: `visibilityState` must be `visible`
 * and frames must actually be counted. A run that cannot see frames fails loudly instead of
 * reporting green — a test environment that cannot observe the thing under test is worse than no
 * test, because it produces confident wrong answers.
 *
 * ── Running it ────────────────────────────────────────────────────────────────────────
 *
 *   npm run build && npm start &        # or: npm run dev
 *   BASE=http://localhost:3000 npm run verify:interactions
 *
 * Uses `playwright-core` against an installed Chrome (`channel: 'chrome'`) rather than the full
 * `playwright` package, so nothing downloads a browser on install.
 */

import { chromium } from 'playwright-core';

const BASE = process.env.BASE ?? 'http://localhost:3000';

/** The stub platform's account. Declared here so it is initialised before the checks run. */
const CREDENTIALS = { email: 'ada@example.com', password: 'correct horse battery staple' };
const wait = (ms) => new Promise((r) => setTimeout(r, ms));

const failures = [];
const checks = [];

function check(name, condition, detail) {
  checks.push({ name, ok: Boolean(condition), detail });
  if (!condition) failures.push(`${name}${detail ? ` — ${detail}` : ''}`);
}

const browser = await chromium.launch({ channel: 'chrome', headless: true });

/**
 * Each section is run inside a catch, so one timeout reports itself as a failed check instead of
 * killing the process and discarding every result gathered so far. A harness that throws away its
 * own findings on the first problem is a harness that makes debugging harder than no harness.
 */
async function section(name, run) {
  try {
    await run();
  } catch (error) {
    check(`${name} completed`, false, error instanceof Error ? error.message.split('\n')[0] : '');
  }
}

try {
  await section('frameloop', verifyFrameloop);
  await section('overlays', verifyOverlays);
  await section('reduced motion', verifyReducedMotion);
} finally {
  await browser.close();
}

for (const { name, ok, detail } of checks) {
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${name}${detail ? `  (${detail})` : ''}`);
}

if (failures.length > 0) {
  console.error(`\n${failures.length} interaction check(s) failed:`);
  for (const f of failures) console.error(`  - ${f}`);
  process.exit(1);
}
console.log(`\nAll ${checks.length} interaction checks passed.`);

// ──────────────────────────────────────────────────────────────────────────────────────

/**
 * The design-system page sits behind the authentication M23.2 added, so every page this suite
 * opens has to sign in first.
 *
 * That is a real coupling and it is the right one: `/foundation` is an application route, and a
 * design system exercised outside the shell it lives in would stop covering the shell. The
 * credentials are the stub platform's — see `scripts/stub-platform.mjs`, which this suite now
 * needs running alongside the portal.
 */

async function signIn(page) {
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });
  // Already signed in: the middleware bounces an authenticated visitor off /login.
  if (!page.url().includes('/login')) return;

  await page.fill('#field-email', CREDENTIALS.email);
  await page.fill('#field-password', CREDENTIALS.password);
  await page.getByRole('button', { name: /sign in/i }).click();

  /*
   * Wait for the URL to actually leave /login, rather than for a load state.
   *
   * This is not defensive padding. Sign-in answers with a redirect that carries the session
   * `Set-Cookie`, and navigating away before that response is consumed *aborts* it — which
   * discards the cookie. The symptom is precise and misleading: the stub shows a successful
   * login, the server shows `ECONNRESET`, and the browser is still sitting on /login with no
   * error message, because nothing failed. It just never received the cookie.
   */
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 15_000 });
  await page.waitForLoadState('networkidle');
}

async function open(path, options = {}) {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, ...options });
  page.on('pageerror', (e) => check(`no page error on ${path}`, false, e.message));
  await signIn(page);
  await page.goto(`${BASE}${path}`, { waitUntil: 'networkidle' });
  // A redirect here means the sign-in did not take, and every check after it would fail with a
  // confusing message about a missing element rather than the real cause.
  check(`${path} is reachable`, !page.url().includes('/login'), page.url());
  return page;
}

function dialogCount(page) {
  return page.locator('[role="dialog"]').count();
}

/** The instrument check. See the note at the top — this is not a formality. */
async function verifyFrameloop() {
  const page = await open('/foundation');
  const env = await page.evaluate(async () => {
    let frames = 0;
    const start = performance.now();
    await new Promise((resolve) => {
      const tick = () => {
        frames++;
        if (performance.now() - start > 300) resolve();
        else requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
    });
    return { visibility: document.visibilityState, frames };
  });
  check('the page is visible to the browser', env.visibility === 'visible', env.visibility);
  check('animation frames are being delivered', env.frames > 5, `${env.frames} frames in 300ms`);
  await page.close();
}

async function verifyOverlays() {
  const page = await open('/foundation');

  // ── command palette ────────────────────────────────────────────────────────────────
  await page.keyboard.press('ControlOrMeta+k');
  await wait(400);
  const palette = await page.evaluate(() => {
    const el = document.querySelector('[role="dialog"]');
    if (!el) return null;
    const cs = getComputedStyle(el);
    return { opacity: Number(cs.opacity), transform: cs.transform };
  });
  check('the command palette opens', palette !== null);
  // The entrance must have *finished*: fully opaque, and back to an identity transform from its
  // initial lift and scale. A frozen page fails here rather than sailing through.
  check('the palette finishes animating in', palette?.opacity === 1, `opacity ${palette?.opacity}`);
  check(
    'the palette settles at its resting transform',
    palette?.transform === 'none',
    palette?.transform,
  );

  // The exit is observable rather than instant: still present and fading one frame in, gone soon
  // after. This is what regressed to nothing when the exit animation was mistakenly removed.
  await page.keyboard.press('Escape');
  await wait(60);
  const midExit = await page.evaluate(() => {
    const el = document.querySelector('[role="dialog"]');
    return el ? Number(getComputedStyle(el).opacity) : null;
  });
  check(
    'the palette animates out rather than vanishing',
    midExit !== null && midExit < 1,
    `opacity ${midExit}`,
  );
  await wait(500);
  check('Escape dismisses the palette', (await dialogCount(page)) === 0);

  await page.keyboard.press('ControlOrMeta+k');
  await wait(350);
  await page.keyboard.press('Enter');
  await wait(500);
  check('Enter selects a result and dismisses the palette', (await dialogCount(page)) === 0);

  await page.goto(`${BASE}/foundation`, { waitUntil: 'networkidle' });
  await page.keyboard.press('ControlOrMeta+k');
  await wait(400);
  await page.mouse.click(20, 700);
  await wait(500);
  check('clicking the scrim dismisses the palette', (await dialogCount(page)) === 0);

  // ── dialog ─────────────────────────────────────────────────────────────────────────
  await page.getByRole('button', { name: /destructive dialog/i }).click();
  await wait(400);
  const dialog = await page.evaluate(() => {
    const el = document.querySelector('[role="dialog"]');
    if (!el) return null;
    const cs = getComputedStyle(el);
    const scrim = [...document.querySelectorAll('div')].find((d) =>
      getComputedStyle(d).backdropFilter?.includes('blur'),
    );
    return {
      opacity: Number(cs.opacity),
      width: Math.round(el.getBoundingClientRect().width),
      blur: scrim ? getComputedStyle(scrim).backdropFilter : null,
    };
  });
  check('the dialog opens', dialog !== null);
  check('the dialog finishes animating in', dialog?.opacity === 1, `opacity ${dialog?.opacity}`);
  check('the scrim is glass, not just a dim', dialog?.blur === 'blur(20px)', dialog?.blur);

  await page.keyboard.press('Escape');
  await wait(500);
  check('Escape dismisses the dialog', (await dialogCount(page)) === 0);

  await page.getByRole('button', { name: /destructive dialog/i }).click();
  await wait(350);
  await page.getByRole('button', { name: 'Cancel' }).click();
  await wait(500);
  check('a footer button dismisses the dialog', (await dialogCount(page)) === 0);

  // ── expandable row ─────────────────────────────────────────────────────────────────
  const row = page.locator('button[aria-controls][aria-expanded]').first();
  await row.click();
  await wait(500);
  check('a table row expands', (await row.getAttribute('aria-expanded')) === 'true');
  const panelHeight = await page.evaluate(() => {
    const b = document.querySelector('button[aria-controls][aria-expanded="true"]');
    const p = b && document.getElementById(b.getAttribute('aria-controls'));
    return p ? Math.round(p.getBoundingClientRect().height) : 0;
  });
  // Height, not just the attribute: `aria-expanded="true"` over a panel stuck at 0px is exactly
  // the failure a DOM-only assertion misses.
  check('the expanded panel has real height', panelHeight > 20, `${panelHeight}px`);
  await row.click();
  await wait(500);
  check('the row collapses again', (await row.getAttribute('aria-expanded')) === 'false');

  // ── metric count-up ────────────────────────────────────────────────────────────────
  const metric = await page.evaluate(() => document.body.innerText.match(/€[\d,.]+/)?.[0] ?? null);
  check(
    'metric tiles finish counting up',
    metric !== null && metric !== '€0.00',
    metric ?? 'absent',
  );

  // ── mobile drawer ──────────────────────────────────────────────────────────────────
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto(`${BASE}/foundation`, { waitUntil: 'networkidle' });
  await page.getByRole('button', { name: 'Open navigation' }).click();
  await wait(500);
  const drawer = await page.evaluate(() => {
    const el = document.querySelector('[role="dialog"]');
    return el ? Math.round(el.getBoundingClientRect().left) : null;
  });
  // The drawer starts fully offscreen. Asserting it reached `left: 0` is what catches an enter
  // animation that never ran — the panel is in the DOM and focus-trapping the page, invisible.
  check('the drawer slides fully into view', drawer === 0, `left ${drawer}`);
  await page.keyboard.press('Escape');
  await wait(500);
  check('Escape dismisses the drawer', (await dialogCount(page)) === 0);

  await page.close();
}

/**
 * With `MotionConfig reducedMotion="user"`, transform animations are dropped — but the element
 * must still arrive at its destination. A drawer that respects the preference by staying
 * offscreen is not accessible, it is broken, and it is broken only for the users who asked for
 * the accommodation.
 */
async function verifyReducedMotion() {
  const page = await open('/foundation', { reducedMotion: 'reduce' });
  await page.setViewportSize({ width: 375, height: 812 });
  await page.reload({ waitUntil: 'networkidle' });
  await page.getByRole('button', { name: 'Open navigation' }).click();
  await wait(400);
  const left = await page.evaluate(() => {
    const el = document.querySelector('[role="dialog"]');
    return el ? Math.round(el.getBoundingClientRect().left) : null;
  });
  check('the drawer is in view under reduced motion', left === 0, `left ${left}`);
  await page.keyboard.press('Escape');
  await wait(400);
  check('the drawer still dismisses under reduced motion', (await dialogCount(page)) === 0);
  await page.close();
}
