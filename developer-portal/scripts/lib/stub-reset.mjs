/**
 * Clearing the platform stub at a scenario boundary, without inheriting the scenario before it.
 *
 * ── The race this closes ──────────────────────────────────────────────────────────────
 *
 * `verify-auth.mjs` runs a dozen scenarios in one process, against one stub and one account.
 * Each opens a Playwright context, signs in, asserts, and closes the context; the ones that read
 * the stub's own state clear it first so they start from a known slate.
 *
 * Closing a browser context does not cancel work the portal has already accepted. A sign-in the
 * browser stopped waiting for still runs to completion server-side, still reaches the platform,
 * and still issues a refresh token. When that lands *after* the next scenario's `__stub/reset`,
 * the token survives into a run that has no idea it exists and therefore never signs it out —
 * and `verifyLogout` reports `no refresh token is left alive — 1 live`. The orphan is real. It
 * belongs to the harness, and at the assertion it is indistinguishable from the portal defect
 * that check exists to catch, which is what made it expensive: the same line has been read as a
 * product bug more than once.
 *
 * So the boundary is made explicit instead of assumed. Wait until the stub has stopped being
 * spoken to, clear it, then confirm it stayed clear — and if a straggler slipped into the gap
 * between those last two steps, do it again. No assertion changes; what changes is that the
 * state each assertion reads is its own scenario's.
 *
 * ── Why quiet is measured at the stub ─────────────────────────────────────────────────
 *
 * The straggler is a request the browser has already let go of, so nothing on the Playwright
 * side can still see it: `context.close()` returns, and the request keeps going. The portal is
 * what holds it, and the stub is where it surfaces. Silence there is the only observable that
 * covers the whole path from browser to platform.
 *
 * `/__stub/calls` records every request the stub receives, its own instrumentation included, so
 * polling it *is* traffic. Only calls the portal made are counted — everything outside
 * `/__stub/` — or the harness would be waiting for itself to stop asking whether it had stopped.
 *
 * ── Seams ─────────────────────────────────────────────────────────────────────────────
 *
 * `fetch`, `wait` and `now` are injectable so `test/stub-reset.test.mjs` can drive this against
 * a fake stub on a virtual clock. A quiet window that costs half a second in a browser run
 * should cost nothing to assert.
 */

/** Requests the portal made, as opposed to the harness's own instrumentation of the stub. */
const isPortalCall = (call) => !String(call?.path ?? '').startsWith('/__stub/');

const realWait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const DEFAULTS = {
  /** How long the stub must go untouched before the previous scenario counts as finished. */
  quietMs: 500,
  pollMs: 50,
  /** A ceiling, not an expectation: this reports failure rather than blocking a suite forever. */
  timeoutMs: 15_000,
  /** Reset attempts before giving up. Two is enough for one straggler; three leaves room. */
  attempts: 3,
};

/**
 * Waits until the portal has stopped talking to the stub.
 *
 * @returns `true` if quiet was reached, `false` if `timeoutMs` expired first. Never throws —
 *   a caller that cannot settle should say so in its own check rather than crash the suite.
 */
export async function waitForQuietStub({
  stub,
  fetch: doFetch = globalThis.fetch,
  wait = realWait,
  now = () => Date.now(),
  quietMs = DEFAULTS.quietMs,
  pollMs = DEFAULTS.pollMs,
  timeoutMs = DEFAULTS.timeoutMs,
} = {}) {
  const deadline = now() + timeoutMs;
  let seen = -1;
  let quietSince = now();

  for (;;) {
    const { calls } = await (await doFetch(`${stub}/__stub/calls`)).json();
    const portalCalls = calls.filter(isPortalCall).length;

    if (portalCalls !== seen) {
      seen = portalCalls;
      quietSince = now();
    } else if (now() - quietSince >= quietMs) {
      return true;
    }

    if (now() >= deadline) return false;
    await wait(pollMs);
  }
}

/**
 * Clears the stub once the scenario before this one has finished with it.
 *
 * @returns a report rather than a boolean, so the caller can put *why* a start was not clean
 *   into its own check detail. `clean` is the one that matters: the stub held no portal traffic
 *   and no live refresh token when this returned.
 */
export async function resetStubWhenQuiet({
  stub,
  fetch: doFetch = globalThis.fetch,
  wait = realWait,
  now = () => Date.now(),
  quietMs = DEFAULTS.quietMs,
  pollMs = DEFAULTS.pollMs,
  timeoutMs = DEFAULTS.timeoutMs,
  attempts = DEFAULTS.attempts,
} = {}) {
  const seams = { stub, fetch: doFetch, wait, now, quietMs, pollMs, timeoutMs };
  let quiesced = false;
  let resets = 0;
  let stragglers = 0;

  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    quiesced = await waitForQuietStub(seams);
    await doFetch(`${stub}/__stub/reset`);
    resets += 1;

    /*
     * A request that was already inside the portal when the reset landed surfaces within a beat
     * of it. Give it that beat and look again: a stub still empty here was empty for a reason,
     * not by luck. This is the confirmation the plain `fetch('/__stub/reset')` never had.
     */
    await wait(quietMs);
    const { calls, liveRefreshTokens } = await (await doFetch(`${stub}/__stub/calls`)).json();
    if (calls.filter(isPortalCall).length === 0 && liveRefreshTokens === 0) {
      return { clean: true, quiesced, resets, stragglers };
    }
    stragglers += 1;
  }

  return { clean: false, quiesced, resets, stragglers };
}
