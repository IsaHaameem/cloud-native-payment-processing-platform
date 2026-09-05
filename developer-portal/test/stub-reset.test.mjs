import { describe, expect, it } from 'vitest';

import { resetStubWhenQuiet, waitForQuietStub } from '../scripts/lib/stub-reset.mjs';

/**
 * The scenario boundary in `verify-auth.mjs`, tested where it can be made deterministic.
 *
 * ── What is being pinned ──────────────────────────────────────────────────────────────
 *
 * `verifyLogout` asserts that signing out leaves no refresh token alive. It read that from a
 * stub the previous scenario could still be writing to: closing a Playwright context does not
 * cancel a sign-in the portal has already accepted, so that sign-in could issue a token *after*
 * the plain `fetch('/__stub/reset')` — an orphan the run never signs out, reported as
 * `1 live` and read, more than once, as the portal failing to revoke.
 *
 * The failure is a timing coincidence measured in tens of milliseconds, so reproducing it
 * through a browser means running the suite until it happens. Here the clock is virtual and the
 * straggler is scheduled, so the race is not reproduced — it is specified.
 *
 * ── The fake ──────────────────────────────────────────────────────────────────────────
 *
 * Small, and faithful in the two ways that decide the outcome: `/__stub/calls` records its own
 * requests exactly as `stub-platform.mjs` does (so a filter that forgot to exclude `/__stub/`
 * would wait for its own polling forever), and a login adds a live refresh token that only
 * `/__stub/reset` removes.
 */
function fakeStub({ portalCalls = [], afterReset } = {}) {
  let clock = 0;
  let liveRefreshTokens = 0;
  const pending = [...portalCalls].sort((a, b) => a.at - b.at);
  const calls = [];
  const resets = [];

  const drain = () => {
    while (pending.length > 0 && pending[0].at <= clock) {
      const call = pending.shift();
      calls.push({ method: 'POST', path: call.path, at: call.at });
      if (call.path === '/api/v1/auth/login') liveRefreshTokens += 1;
    }
  };

  const stub = {
    get clock() {
      return clock;
    },
    get resets() {
      return resets;
    },
    get liveRefreshTokens() {
      return liveRefreshTokens;
    },
    /** A straggler, scheduled from inside a reset — which is where the real one arrives. */
    schedule(call) {
      pending.push(call);
      pending.sort((a, b) => a.at - b.at);
    },
    now: () => clock,
    wait: async (ms) => {
      clock += ms;
      drain();
    },
    fetch: async (url) => {
      drain();
      if (String(url).endsWith('/__stub/reset')) {
        calls.length = 0;
        liveRefreshTokens = 0;
        resets.push(clock);
        afterReset?.(stub, resets.length);
        return { json: async () => ({ ok: true }) };
      }
      calls.push({ method: 'GET', path: '/__stub/calls', at: clock });
      const snapshot = { calls: calls.map((call) => ({ ...call })), liveRefreshTokens };
      return { json: async () => snapshot };
    },
  };

  return stub;
}

const seams = (stub) => ({
  stub: 'http://stub.test',
  fetch: stub.fetch,
  wait: stub.wait,
  now: stub.now,
});

describe('resetStubWhenQuiet', () => {
  it('clears an idle stub after one quiet window, ignoring its own polling', async () => {
    const stub = fakeStub();

    const report = await resetStubWhenQuiet(seams(stub));

    expect(report).toMatchObject({ clean: true, quiesced: true, resets: 1, stragglers: 0 });
    // Exactly the quiet window, which is only possible if the poller does not count itself:
    // `/__stub/calls` grows on every read, and treating that as traffic never settles.
    expect(stub.resets).toEqual([500]);
  });

  it('does not clear the stub while the previous scenario is still talking to it', async () => {
    const stub = fakeStub({
      portalCalls: [
        { at: 0, path: '/api/v1/auth/login' },
        { at: 200, path: '/api/v1/auth/refresh' },
        { at: 400, path: '/api/v1/auth/refresh' },
        { at: 600, path: '/api/v1/auth/refresh' },
        { at: 800, path: '/api/v1/auth/refresh' },
        { at: 900, path: '/api/v1/auth/refresh' },
      ],
    });

    const report = await resetStubWhenQuiet(seams(stub));

    expect(report).toMatchObject({ clean: true, resets: 1, stragglers: 0 });
    // A full quiet window after the *last* call, not after the first lull between them.
    expect(stub.resets).toEqual([1400]);
    expect(stub.liveRefreshTokens).toBe(0);
  });

  it('clears again when a call lands after the quiet window had already elapsed', async () => {
    const stub = fakeStub({
      portalCalls: [
        { at: 0, path: '/api/v1/auth/login' },
        { at: 200, path: '/api/v1/auth/refresh' },
        // 700ms after the one before it. Waiting for quiet is a judgement about when a scenario
        // has finished, and no threshold makes that judgement infallible — which is why the
        // reset is confirmed afterwards rather than trusted. That confirmation is what turns a
        // straggler from a corrupted run into a second reset.
        { at: 900, path: '/api/v1/auth/refresh' },
      ],
    });

    const report = await resetStubWhenQuiet(seams(stub));

    expect(report).toMatchObject({ clean: true, resets: 2, stragglers: 1 });
    expect(stub.resets).toEqual([700, 1700]);
    expect(stub.liveRefreshTokens).toBe(0);
  });

  it('clears again when a straggling sign-in lands in the gap just after a reset', async () => {
    const stub = fakeStub({
      portalCalls: [{ at: 0, path: '/api/v1/auth/login' }],
      // The race itself: a sign-in the portal had already accepted, reaching the platform ten
      // milliseconds after the harness thought it had a clean slate.
      afterReset: (self, resetNumber) => {
        if (resetNumber === 1) self.schedule({ at: self.clock + 10, path: '/api/v1/auth/login' });
      },
    });

    const report = await resetStubWhenQuiet(seams(stub));

    expect(report).toMatchObject({ clean: true, resets: 2, stragglers: 1 });
    // The assertion `verifyLogout` makes. Before this fix it read 1, and blamed the portal.
    expect(stub.liveRefreshTokens).toBe(0);
  });

  it('reports an unclean start rather than pretending, when stragglers never stop', async () => {
    const stub = fakeStub({
      portalCalls: [{ at: 0, path: '/api/v1/auth/login' }],
      afterReset: (self) => self.schedule({ at: self.clock + 10, path: '/api/v1/auth/login' }),
    });

    const report = await resetStubWhenQuiet(seams(stub));

    // `clean: false` is what makes `verifyLogout`'s precondition check fail in the harness's
    // own name. Silently continuing here is how the orphan got mistaken for a portal defect.
    expect(report).toMatchObject({ clean: false, resets: 3, stragglers: 3 });
  });
});

describe('waitForQuietStub', () => {
  it('gives up at the timeout rather than blocking the suite forever', async () => {
    const stub = fakeStub({
      portalCalls: Array.from({ length: 2000 }, (_, i) => ({
        at: i * 25,
        path: '/api/v1/auth/refresh',
      })),
    });

    const quiet = await waitForQuietStub({ ...seams(stub), timeoutMs: 15_000 });

    expect(quiet).toBe(false);
    expect(stub.clock).toBeGreaterThanOrEqual(15_000);
    expect(stub.clock).toBeLessThan(16_000);
    expect(stub.resets).toEqual([]);
  });
});
