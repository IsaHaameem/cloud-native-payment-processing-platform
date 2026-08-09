import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The origin check that produced M23.2b's bug report (M23.2b).
 *
 * ── The regression this file exists for ───────────────────────────────────────────────
 *
 * The portal was configured for `http://localhost:3000` and reached at `http://127.0.0.1:3000` —
 * the same server, the same machine, a spelling a browser offers by itself. Every state-changing
 * form was refused, and because a cross-origin refusal and a stale CSRF token shared one message,
 * the screen said *"this form expired before it was submitted"* about a form rendered a second
 * earlier. It was reported as a signup bug and it was neither signup nor CSRF.
 *
 * `accepts 127.0.0.1 when that is the address the request was sent to` is the regression itself —
 * it fails on the code as it stood. `is inert for a portal configured for a real domain` is the
 * other half: the fix must not become a hole on a deployment.
 *
 * ── `env` is stubbed per test, so several configurations are exercised in one run ─────
 *
 * `lib/env.ts` reads `process.env` once at module load, which is exactly what it should do and
 * exactly what makes it awkward to test two environments. Mocking the module is the narrow answer:
 * the code under test is the *decision*, not the parsing, and `env.test.ts`-style parsing coverage
 * would not have caught this bug in any case.
 */

const envMock = vi.hoisted(() => ({
  publicOrigin: 'http://localhost:3000',
  additionalOrigins: [] as readonly string[],
  isProduction: false,
}));

vi.mock('@/lib/env', () => ({ env: envMock }));

const { CrossOriginRequestError, assertSameOrigin, isSameOrigin } =
  await import('@/lib/security/origin');

function headers(entries: Record<string, string>): Headers {
  return new Headers(entries);
}

beforeEach(() => {
  envMock.publicOrigin = 'http://localhost:3000';
  envMock.additionalOrigins = [];
  envMock.isProduction = false;
  // The refusal path logs, deliberately — silenced here so a suite of expected refusals does not
  // bury a real warning in noise.
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('the configured origin', () => {
  it('accepts the canonical origin', () => {
    expect(() =>
      assertSameOrigin(headers({ origin: 'http://localhost:3000', host: 'localhost:3000' })),
    ).not.toThrow();
  });

  it('accepts an explicitly configured additional origin', () => {
    envMock.additionalOrigins = ['https://portal.example.com'];
    expect(
      isSameOrigin(headers({ origin: 'https://portal.example.com', host: 'portal.example.com' })),
    ).toBe(true);
  });

  it('refuses an origin that is not configured', () => {
    expect(() =>
      assertSameOrigin(headers({ origin: 'https://evil.test', host: 'localhost:3000' })),
    ).toThrow(CrossOriginRequestError);
  });
});

describe('reaching a development server by another name — the M23.2b regression', () => {
  it('accepts 127.0.0.1 when that is the address the request was sent to', () => {
    // Configured for localhost, reached at 127.0.0.1. This threw before M23.2b, and the user was
    // told their signup form had expired.
    expect(() =>
      assertSameOrigin(headers({ origin: 'http://127.0.0.1:3000', host: '127.0.0.1:3000' })),
    ).not.toThrow();
  });

  it('accepts an IPv6 loopback the same way', () => {
    expect(isSameOrigin(headers({ origin: 'http://[::1]:3000', host: '[::1]:3000' }))).toBe(true);
  });

  it('still refuses a loopback origin that is not the host addressed', () => {
    // The narrowing that keeps this from being a hole: a request *to* localhost claiming to come
    // *from* 127.0.0.1 is not same-origin, and no browser would send it.
    expect(isSameOrigin(headers({ origin: 'http://127.0.0.1:3000', host: 'localhost:3000' }))).toBe(
      false,
    );
  });

  it('still refuses an off-site origin, whatever the host says', () => {
    expect(isSameOrigin(headers({ origin: 'https://evil.test', host: '127.0.0.1:3000' }))).toBe(
      false,
    );
  });

  it('is inert for a portal configured for a real domain', () => {
    // The gate. A deployment on the internet never enters the loopback branch at all, so the
    // allowance cannot be what admits an attacker to a real site.
    envMock.publicOrigin = 'https://portal.example.com';
    expect(isSameOrigin(headers({ origin: 'http://127.0.0.1:3000', host: '127.0.0.1:3000' }))).toBe(
      false,
    );
  });

  it('still applies to a production build served on localhost', () => {
    // `docker compose up` runs the production bundle on http://localhost:3000. Gating this on
    // NODE_ENV instead of on the configured origin would have left that case broken, which is
    // most of how the portal is actually run locally.
    envMock.isProduction = true;
    expect(isSameOrigin(headers({ origin: 'http://127.0.0.1:3000', host: '127.0.0.1:3000' }))).toBe(
      true,
    );
  });

  it('refuses a non-loopback host match even when configured locally', () => {
    // Matching Host alone is not enough: Host is attacker-influenced behind a proxy, so the
    // allowance is confined to names that can only mean this machine.
    expect(
      isSameOrigin(
        headers({ origin: 'http://portal.internal:3000', host: 'portal.internal:3000' }),
      ),
    ).toBe(false);
  });
});

describe('the fallback and the refusals that must not change', () => {
  it('falls back to Sec-Fetch-Site when Origin is absent', () => {
    expect(isSameOrigin(headers({ 'sec-fetch-site': 'same-origin' }))).toBe(true);
    expect(isSameOrigin(headers({ 'sec-fetch-site': 'none' }))).toBe(true);
    expect(isSameOrigin(headers({ 'sec-fetch-site': 'cross-site' }))).toBe(false);
  });

  it('refuses a sibling subdomain, which SameSite considers same-site', () => {
    expect(isSameOrigin(headers({ 'sec-fetch-site': 'same-site' }))).toBe(false);
    expect(
      isSameOrigin(headers({ origin: 'http://evil.localhost:3000', host: 'localhost:3000' })),
    ).toBe(false);
  });

  it('refuses a request carrying neither header', () => {
    expect(isSameOrigin(headers({}))).toBe(false);
  });

  it('prefers Origin over a forged Sec-Fetch-Site', () => {
    expect(
      isSameOrigin(headers({ origin: 'https://evil.test', 'sec-fetch-site': 'same-origin' })),
    ).toBe(false);
  });

  it('refuses a scheme change on the same host', () => {
    // https://localhost:3000 is a different origin from http://localhost:3000, and the loopback
    // allowance must not paper over that — the scheme is part of what "same origin" means.
    expect(
      isSameOrigin(headers({ origin: 'https://localhost:3000', host: 'localhost:3000' })),
    ).toBe(false);
  });

  it('explains the refusal to whoever is reading the logs', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    isSameOrigin(headers({ origin: 'http://127.0.0.1:9999', host: 'localhost:3000' }));

    // The diagnostic whose absence cost a bug report: what arrived, what is accepted, and which
    // variable to change.
    const message = String(warn.mock.calls[0]?.[0] ?? '');
    expect(message).toContain('http://127.0.0.1:9999');
    expect(message).toContain('http://localhost:3000');
    expect(message).toContain('PORTAL_PUBLIC_ORIGIN');
  });
});
