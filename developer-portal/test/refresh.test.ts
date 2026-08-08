import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { IdentityUnavailableError, RejectedTokenError } from '@/lib/session/identity';
import {
  refreshCoordinatorStateForTesting,
  refreshSession,
  resetRefreshCoordinatorForTesting,
} from '@/lib/session/refresh';
import { SESSION_VERSION, type Session } from '@/lib/session/session';

/**
 * Refresh coordination (M23.2) — the milestone's central risk.
 *
 * ── What is being defended against, concretely ────────────────────────────────────────
 *
 * `RefreshTokenService.rotate` in identity-service revokes the presented token and issues a new
 * one. `RefreshToken` carries no `@Version`, so two concurrent rotations both succeed and mint
 * two live tokens — and any request arriving after the first commit presents a revoked token and
 * gets 401. Uncoordinated, that is a user signed out in the middle of loading a page.
 *
 * These tests assert the two mechanisms that prevent it, and — critically — they assert them by
 * *counting calls to identity-service*. A test that only checked the returned tokens would pass
 * against a completely uncoordinated implementation.
 */

const rotateAtIdentity = vi.hoisted(() => vi.fn());

vi.mock('@/lib/session/identity', async () => {
  const actual =
    await vi.importActual<typeof import('@/lib/session/identity')>('@/lib/session/identity');
  return { ...actual, refresh: rotateAtIdentity };
});

function aSession(refreshToken: string): Session {
  const now = Date.now();
  return {
    version: SESSION_VERSION,
    userId: '11111111-2222-3333-4444-555555555555',
    email: 'ada@example.com',
    roles: ['USER'],
    accessToken: 'expired.access.token',
    accessExpiresAt: now - 1000,
    refreshToken,
    refreshExpiresAt: now + 7 * 24 * 60 * 60 * 1000,
    merchantId: undefined,
    mode: 'test',
    createdAt: now,
  };
}

/** A rotation that takes a tick, so overlapping callers genuinely overlap. */
function slowRotation(suffix: string, delayMs = 20) {
  return async () => {
    await new Promise((resolve) => setTimeout(resolve, delayMs));
    return {
      accessToken: `access-${suffix}`,
      refreshToken: `refresh-${suffix}`,
      accessExpiresAt: Date.now() + 15 * 60 * 1000,
    };
  };
}

beforeEach(() => {
  resetRefreshCoordinatorForTesting();
  rotateAtIdentity.mockReset();
});

afterEach(() => {
  resetRefreshCoordinatorForTesting();
});

describe('concurrent refresh', () => {
  it('collapses ten simultaneous refreshes into exactly one rotation', async () => {
    rotateAtIdentity.mockImplementation(slowRotation('one'));
    const session = aSession('the-original-token');

    const results = await Promise.all(Array.from({ length: 10 }, () => refreshSession(session)));

    // The property that matters. Ten calls here would mean nine revoked tokens and a logout.
    expect(rotateAtIdentity).toHaveBeenCalledTimes(1);

    // And every caller got the same working credentials, rather than nine of them getting an error.
    for (const result of results) {
      expect(result.accessToken).toBe('access-one');
      expect(result.refreshToken).toBe('refresh-one');
    }
  });

  it('gives every concurrent caller the same rotated token, not one winner and nine losers', async () => {
    rotateAtIdentity.mockImplementation(slowRotation('shared'));
    const session = aSession('the-original-token');

    const tokens = new Set(
      (await Promise.all(Array.from({ length: 25 }, () => refreshSession(session)))).map(
        (s) => s.refreshToken,
      ),
    );

    expect(tokens).toEqual(new Set(['refresh-shared']));
  });

  it('does not conflate two different sessions', async () => {
    rotateAtIdentity.mockImplementation(async (token: string) => {
      await new Promise((resolve) => setTimeout(resolve, 10));
      return {
        accessToken: `access-for-${token}`,
        refreshToken: `refresh-for-${token}`,
        accessExpiresAt: Date.now() + 15 * 60 * 1000,
      };
    });

    const [ada, grace] = await Promise.all([
      refreshSession(aSession('ada-token')),
      refreshSession(aSession('grace-token')),
    ]);

    // The mutex is keyed by token, so two users refreshing at once must not share a result —
    // which would hand one user the other's credentials.
    expect(rotateAtIdentity).toHaveBeenCalledTimes(2);
    expect(ada.accessToken).toBe('access-for-ada-token');
    expect(grace.accessToken).toBe('access-for-grace-token');
  });

  it('releases the mutex so a later refresh of a new token still happens', async () => {
    rotateAtIdentity.mockImplementation(slowRotation('first'));
    const first = await refreshSession(aSession('token-a'));

    rotateAtIdentity.mockImplementation(slowRotation('second'));
    const second = await refreshSession({ ...first, refreshToken: 'token-b' });

    expect(rotateAtIdentity).toHaveBeenCalledTimes(2);
    expect(second.refreshToken).toBe('refresh-second');
    expect(refreshCoordinatorStateForTesting().inFlight).toBe(0);
  });
});

describe('replay of a rotation that could not be persisted', () => {
  it('returns the cached result rather than rotating a token that is now revoked', async () => {
    rotateAtIdentity.mockImplementation(slowRotation('once'));
    const session = aSession('lost-write-token');

    // First call: this is the render that rotated but could not write a cookie.
    const rotated = await refreshSession(session);
    expect(rotateAtIdentity).toHaveBeenCalledTimes(1);

    // Next request arrives still holding the *old* token, because the write was lost. Without
    // the replay cache this presents a revoked token to identity-service, gets 401, and signs
    // the user out — a logout caused entirely by where the refresh happened.
    const replayed = await refreshSession(session);

    expect(rotateAtIdentity).toHaveBeenCalledTimes(1);
    expect(replayed.refreshToken).toBe(rotated.refreshToken);
    expect(replayed.accessToken).toBe(rotated.accessToken);
  });

  it('does not cache a rejection, so a token rotated elsewhere can still be retried', async () => {
    rotateAtIdentity.mockRejectedValueOnce(new RejectedTokenError('nope'));
    const session = aSession('contested-token');

    await expect(refreshSession(session)).rejects.toBeInstanceOf(RejectedTokenError);

    // A rejection cached would make a transient loss permanent for the life of the entry.
    rotateAtIdentity.mockImplementation(slowRotation('recovered'));
    await expect(refreshSession(session)).resolves.toMatchObject({
      refreshToken: 'refresh-recovered',
    });
    expect(rotateAtIdentity).toHaveBeenCalledTimes(2);
  });
});

describe('failure classification', () => {
  it('propagates a rejected token, because the session really is over', async () => {
    rotateAtIdentity.mockRejectedValue(new RejectedTokenError('revoked'));
    await expect(refreshSession(aSession('dead-token'))).rejects.toBeInstanceOf(RejectedTokenError);
  });

  it('propagates unavailability distinctly, because the session is *not* over', async () => {
    rotateAtIdentity.mockRejectedValue(new IdentityUnavailableError('down'));
    // The distinction is the whole point: conflating these signs every user out during a deploy.
    await expect(refreshSession(aSession('fine-token'))).rejects.toBeInstanceOf(
      IdentityUnavailableError,
    );
  });

  it('lets all concurrent callers see the same failure, once', async () => {
    rotateAtIdentity.mockImplementation(async () => {
      await new Promise((resolve) => setTimeout(resolve, 10));
      throw new IdentityUnavailableError('down');
    });

    const outcomes = await Promise.allSettled(
      Array.from({ length: 8 }, () => refreshSession(aSession('shared-token'))),
    );

    expect(rotateAtIdentity).toHaveBeenCalledTimes(1);
    expect(outcomes.every((o) => o.status === 'rejected')).toBe(true);
    expect(refreshCoordinatorStateForTesting().inFlight).toBe(0);
  });
});
