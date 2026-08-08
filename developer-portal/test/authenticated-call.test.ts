import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { callAs } from '@/lib/api/client';
import { AuthenticationError, PermissionDeniedError } from '@/lib/api/errors';
import { IdentityUnavailableError, RejectedTokenError } from '@/lib/session/identity';
import { resetRefreshCoordinatorForTesting } from '@/lib/session/refresh';
import { SESSION_VERSION, type Session } from '@/lib/session/session';

/**
 * The authenticated call path (M23.2): credentials on the request, and the single retry.
 *
 * `fetch` is stubbed, so the real transport builds the real request — which is what lets the
 * first test assert on the headers the gateway will actually receive.
 */

const rotateAtIdentity = vi.hoisted(() => vi.fn());
const writeSessionMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/session/identity', async () => {
  const actual =
    await vi.importActual<typeof import('@/lib/session/identity')>('@/lib/session/identity');
  return { ...actual, refresh: rotateAtIdentity };
});

vi.mock('@/lib/session/store', () => ({
  writeSession: writeSessionMock,
  readSessionCookie: vi.fn(),
  clearSession: vi.fn(),
}));

const fetchMock = vi.fn();

function aSession(overrides: Partial<Session> = {}): Session {
  const now = Date.now();
  return {
    version: SESSION_VERSION,
    userId: '11111111-2222-3333-4444-555555555555',
    email: 'ada@example.com',
    roles: ['USER'],
    accessToken: 'the-original-access-token',
    accessExpiresAt: now + 15 * 60 * 1000,
    refreshToken: 'the-original-refresh-token',
    refreshExpiresAt: now + 7 * 24 * 60 * 60 * 1000,
    merchantId: '66666666-7777-8888-9999-000000000000',
    mode: 'test',
    createdAt: now,
    ...overrides,
  };
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

const UNAUTHORIZED = { type: 'authentication_error', code: 'unauthorized', message: 'nope' };

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  rotateAtIdentity.mockReset();
  writeSessionMock.mockReset().mockResolvedValue(true);
  resetRefreshCoordinatorForTesting();
});

afterEach(() => {
  vi.unstubAllGlobals();
  resetRefreshCoordinatorForTesting();
});

describe('callAs', () => {
  it('sends the session credentials the gateway expects', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(200, { data: [] })));

    await callAs('listPayments', { session: aSession({ mode: 'live' }) });

    const headers = new Headers(fetchMock.mock.calls[0]?.[1].headers as HeadersInit);
    expect(headers.get('authorization')).toBe('Bearer the-original-access-token');
    // Consumed and validated by M23.0's gateway filter, never forwarded downstream.
    expect(headers.get('x-pf-mode')).toBe('live');
  });

  it('refreshes once and retries after a 401', async () => {
    fetchMock
      .mockImplementationOnce(() => Promise.resolve(jsonResponse(401, UNAUTHORIZED)))
      .mockImplementationOnce(() => Promise.resolve(jsonResponse(200, { data: ['ok'] })));
    rotateAtIdentity.mockResolvedValue({
      accessToken: 'the-rotated-access-token',
      refreshToken: 'the-rotated-refresh-token',
      accessExpiresAt: Date.now() + 900_000,
    });

    await expect(callAs('listPayments', { session: aSession() })).resolves.toEqual({
      data: ['ok'],
    });

    expect(rotateAtIdentity).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);

    // The retry must carry the *new* token — retrying with the old one is a loop, not a recovery.
    const retryHeaders = new Headers(fetchMock.mock.calls[1]?.[1].headers as HeadersInit);
    expect(retryHeaders.get('authorization')).toBe('Bearer the-rotated-access-token');
  });

  it('persists the rotated session', async () => {
    fetchMock
      .mockImplementationOnce(() => Promise.resolve(jsonResponse(401, UNAUTHORIZED)))
      .mockImplementationOnce(() => Promise.resolve(jsonResponse(200, {})));
    rotateAtIdentity.mockResolvedValue({
      accessToken: 'a2',
      refreshToken: 'r2',
      accessExpiresAt: Date.now() + 900_000,
    });

    await callAs('listPayments', { session: aSession() });

    expect(writeSessionMock).toHaveBeenCalledTimes(1);
    expect(writeSessionMock.mock.calls[0]?.[0]).toMatchObject({
      accessToken: 'a2',
      refreshToken: 'r2',
    });
  });

  it('still returns the data when the cookie could not be written', async () => {
    // The Server Component case. The rotation is not lost — `refresh.ts` holds it for the next
    // request, which arrives at middleware and can persist it.
    writeSessionMock.mockResolvedValue(false);
    fetchMock
      .mockImplementationOnce(() => Promise.resolve(jsonResponse(401, UNAUTHORIZED)))
      .mockImplementationOnce(() => Promise.resolve(jsonResponse(200, { data: ['ok'] })));
    rotateAtIdentity.mockResolvedValue({
      accessToken: 'a2',
      refreshToken: 'r2',
      accessExpiresAt: Date.now() + 900_000,
    });

    await expect(callAs('listPayments', { session: aSession() })).resolves.toEqual({
      data: ['ok'],
    });
  });

  it('retries exactly once — a second 401 is the end of the session', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(401, UNAUTHORIZED)));
    rotateAtIdentity.mockResolvedValue({
      accessToken: 'a2',
      refreshToken: 'r2',
      accessExpiresAt: Date.now() + 900_000,
    });

    await expect(callAs('listPayments', { session: aSession() })).rejects.toBeInstanceOf(
      AuthenticationError,
    );
    // Two attempts, one rotation. Anything more is a retry loop against a dead credential.
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(rotateAtIdentity).toHaveBeenCalledTimes(1);
  });

  it('surfaces the original 401 when the refresh token is also dead', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(401, UNAUTHORIZED)));
    rotateAtIdentity.mockRejectedValue(new RejectedTokenError('revoked'));

    await expect(callAs('listPayments', { session: aSession() })).rejects.toBeInstanceOf(
      AuthenticationError,
    );
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('surfaces the original 401 when identity is unreachable, not the outage', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(401, UNAUTHORIZED)));
    rotateAtIdentity.mockRejectedValue(new IdentityUnavailableError('down'));

    // The caller asked "did this call work". The honest answer is the gateway's answer.
    await expect(callAs('listPayments', { session: aSession() })).rejects.toBeInstanceOf(
      AuthenticationError,
    );
  });

  it('does not refresh for a 403, which is a different problem entirely', async () => {
    fetchMock.mockImplementation(() =>
      Promise.resolve(
        jsonResponse(403, { type: 'permission_error', code: 'forbidden', message: 'no merchant' }),
      ),
    );

    // M23.0's gateway answers a merchant-less session with 403. Refreshing would rotate a
    // perfectly good token to solve a problem that is not about credentials.
    await expect(callAs('listPayments', { session: aSession() })).rejects.toBeInstanceOf(
      PermissionDeniedError,
    );
    expect(rotateAtIdentity).not.toHaveBeenCalled();
  });

  it('coordinates the retry refresh with every other refresher', async () => {
    fetchMock.mockImplementation((_url: string, init: RequestInit) => {
      const headers = new Headers(init.headers as HeadersInit);
      return Promise.resolve(
        headers.get('authorization') === 'Bearer the-original-access-token'
          ? jsonResponse(401, UNAUTHORIZED)
          : jsonResponse(200, { data: [] }),
      );
    });
    rotateAtIdentity.mockImplementation(async () => {
      await new Promise((resolve) => setTimeout(resolve, 15));
      return {
        accessToken: 'a2',
        refreshToken: 'r2',
        accessExpiresAt: Date.now() + 900_000,
      };
    });

    const session = aSession();
    await Promise.all(Array.from({ length: 6 }, () => callAs('listPayments', { session })));

    // Six parallel calls all hit 401 and all need a refresh — and there is exactly one rotation,
    // because this path shares the coordinator with the middleware rather than having its own.
    expect(rotateAtIdentity).toHaveBeenCalledTimes(1);
  });
});
