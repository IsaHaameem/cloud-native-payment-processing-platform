import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { readUnverifiedClaims } from '@/lib/session/claims';
import {
  IdentityUnavailableError,
  InvalidCredentialsError,
  RejectedTokenError,
  login,
  logout,
  refresh,
} from '@/lib/session/identity';
import { LOGIN_THROTTLE_LIMITS, resetLoginThrottleForTesting } from '@/lib/security/login-throttle';
import { establishSession } from '@/lib/session/lifecycle';

/**
 * The authentication flows against a stubbed platform (M23.2).
 *
 * `fetch` is replaced rather than the modules that call it, so these exercise the real request
 * building, the real status handling and the real error classification — everything except the
 * network.
 */

const fetchMock = vi.fn();

/** Builds a JWT-shaped token. Unsigned: nothing in the portal verifies signatures, by design. */
function accessTokenFor(claims: Record<string, unknown>): string {
  const encode = (value: unknown) =>
    Buffer.from(JSON.stringify(value)).toString('base64url').replace(/=+$/, '');
  return `${encode({ alg: 'RS256' })}.${encode(claims)}.signature`;
}

const VALID_CLAIMS = {
  sub: '11111111-2222-3333-4444-555555555555',
  email: 'ada@example.com',
  roles: ['USER'],
  exp: Math.floor(Date.now() / 1000) + 900,
};

function jsonResponse(status: number, body: unknown): Response {
  return new Response(body === undefined ? '' : JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  resetLoginThrottleForTesting();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('identity error classification', () => {
  it('maps 401 on login to invalid credentials', async () => {
    fetchMock.mockResolvedValue(jsonResponse(401, { code: 'unauthorized' }));
    await expect(login('ada@example.com', 'wrong')).rejects.toBeInstanceOf(InvalidCredentialsError);
  });

  it('maps a validation rejection to invalid credentials too, so nothing is enumerable', async () => {
    fetchMock.mockResolvedValue(jsonResponse(400, { code: 'validation_error' }));
    await expect(login('not-an-email', 'x')).rejects.toBeInstanceOf(InvalidCredentialsError);
  });

  it('maps 5xx to unavailability, never to a credential problem', async () => {
    // The distinction that stops a backend blip from signing out every user in the portal.
    fetchMock.mockResolvedValue(jsonResponse(503, { code: 'service_unavailable' }));
    await expect(login('ada@example.com', 'right')).rejects.toBeInstanceOf(
      IdentityUnavailableError,
    );
  });

  it('maps a network failure to unavailability', async () => {
    fetchMock.mockRejectedValue(new TypeError('fetch failed'));
    await expect(login('ada@example.com', 'right')).rejects.toBeInstanceOf(
      IdentityUnavailableError,
    );
  });

  it('maps 401 on refresh to a rejected token', async () => {
    fetchMock.mockResolvedValue(jsonResponse(401, { code: 'unauthorized' }));
    await expect(refresh('revoked')).rejects.toBeInstanceOf(RejectedTokenError);
  });

  it('treats a 200 that carries no tokens as unavailability, not as a session', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { accessToken: 'only-one' }));
    await expect(refresh('token')).rejects.toBeInstanceOf(IdentityUnavailableError);
  });

  it('never puts a token in an error message', async () => {
    fetchMock.mockResolvedValue(jsonResponse(503, {}));
    const error = await refresh('super-secret-refresh-token').then(
      () => new Error('expected a rejection'),
      (e: unknown) => e as Error,
    );
    expect(error.message).not.toContain('super-secret-refresh-token');
  });

  it('reports a failed logout rather than throwing, so sign-out always proceeds', async () => {
    fetchMock.mockRejectedValue(new TypeError('fetch failed'));
    await expect(logout('token')).resolves.toBe(false);
  });
});

describe('claims', () => {
  it('reads the identity out of a well-formed token', () => {
    expect(readUnverifiedClaims(accessTokenFor(VALID_CLAIMS))).toMatchObject({
      userId: VALID_CLAIMS.sub,
      email: 'ada@example.com',
      roles: ['USER'],
    });
  });

  it('refuses a token with no subject, because a session must know who it is', () => {
    expect(readUnverifiedClaims(accessTokenFor({ email: 'ada@example.com' }))).toBeNull();
  });

  it('refuses anything that is not a three-part JWT', () => {
    expect(readUnverifiedClaims('not-a-jwt')).toBeNull();
    expect(readUnverifiedClaims('a.b')).toBeNull();
    expect(readUnverifiedClaims('a.!!!.c')).toBeNull();
  });

  it('tolerates a missing roles claim rather than failing the login', () => {
    expect(readUnverifiedClaims(accessTokenFor({ sub: VALID_CLAIMS.sub }))?.roles).toEqual([]);
  });
});

describe('establishSession', () => {
  function respondTo(url: string): Response {
    if (url.endsWith('/api/v1/auth/login')) {
      return jsonResponse(200, {
        accessToken: accessTokenFor(VALID_CLAIMS),
        refreshToken: 'the-refresh-token',
        expiresIn: 900,
      });
    }
    if (url.endsWith('/api/v1/merchants/me')) {
      return jsonResponse(200, { id: '66666666-7777-8888-9999-000000000000' });
    }
    return jsonResponse(404, {});
  }

  it('builds a session from a successful sign-in', async () => {
    fetchMock.mockImplementation((url: string) => Promise.resolve(respondTo(url)));

    const result = await establishSession('Ada@Example.com', 'correct horse');
    expect(result.ok).toBe(true);
    if (!result.ok) return;

    expect(result.session).toMatchObject({
      userId: VALID_CLAIMS.sub,
      email: 'ada@example.com',
      roles: ['USER'],
      accessToken: accessTokenFor(VALID_CLAIMS),
      refreshToken: 'the-refresh-token',
      merchantId: '66666666-7777-8888-9999-000000000000',
      // Always test on a fresh sign-in: a portal that remembered `live` would show a returning
      // user real money before they asked for it.
      mode: 'test',
    });
    expect(result.session.accessExpiresAt).toBeGreaterThan(Date.now());
  });

  it('normalises the email the same way identity-service does', async () => {
    fetchMock.mockImplementation((url: string) => Promise.resolve(respondTo(url)));
    await establishSession('  Ada@Example.COM ', 'correct horse');

    const body = JSON.parse(fetchMock.mock.calls[0]?.[1].body as string) as { email: string };
    // Otherwise `Ada@…` and `ada@…` are two throttle buckets for one account.
    expect(body.email).toBe('ada@example.com');
  });

  it('signs in a user who has no merchant yet', async () => {
    fetchMock.mockImplementation((url: string) =>
      Promise.resolve(
        url.endsWith('/merchants/me') ? jsonResponse(404, { code: 'not_found' }) : respondTo(url),
      ),
    );

    const result = await establishSession('ada@example.com', 'correct horse');
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    // Signed in but not onboarded — a real state, and not a failed login.
    expect(result.session.merchantId).toBeUndefined();
  });

  it('still signs in when the merchant lookup fails outright', async () => {
    fetchMock.mockImplementation((url: string) =>
      url.endsWith('/merchants/me')
        ? Promise.reject(new TypeError('fetch failed'))
        : Promise.resolve(respondTo(url)),
    );

    // Refusing a login because a *secondary* fact could not be established would be the wrong
    // trade: the user lands on onboarding, which re-checks.
    const result = await establishSession('ada@example.com', 'correct horse');
    expect(result.ok).toBe(true);
  });

  it('reports bad credentials without revealing which half was wrong', async () => {
    fetchMock.mockResolvedValue(jsonResponse(401, {}));
    await expect(establishSession('ada@example.com', 'wrong')).resolves.toEqual({
      ok: false,
      reason: 'invalid_credentials',
    });
  });

  it('throttles an account after repeated failures', async () => {
    // A fresh Response per call: a body can only be read once, so a single shared instance
    // would fail the second attempt for the wrong reason.
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(401, {})));

    for (let i = 0; i < LOGIN_THROTTLE_LIMITS.maxAttempts; i++) {
      await establishSession('ada@example.com', 'wrong');
    }
    const callsBefore = fetchMock.mock.calls.length;

    await expect(establishSession('ada@example.com', 'wrong')).resolves.toEqual({
      ok: false,
      reason: 'throttled',
    });
    // Checked before the call, so a throttled attempt does not spend the gateway's shared bucket.
    expect(fetchMock.mock.calls.length).toBe(callsBefore);
  });

  it('does not throttle an account for the platform being unwell', async () => {
    fetchMock.mockResolvedValue(jsonResponse(503, {}));

    for (let i = 0; i < LOGIN_THROTTLE_LIMITS.maxAttempts + 2; i++) {
      await expect(establishSession('ada@example.com', 'right')).resolves.toEqual({
        ok: false,
        reason: 'unavailable',
      });
    }
  });

  it('refuses to build a session from a token it cannot describe', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(200, { accessToken: 'not-a-jwt', refreshToken: 'r', expiresIn: 900 }),
    );
    // Better to fail the sign-in than to seal a cookie whose `userId` is invented.
    await expect(establishSession('ada@example.com', 'right')).resolves.toEqual({
      ok: false,
      reason: 'unavailable',
    });
  });
});
