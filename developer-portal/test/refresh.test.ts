import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { IdentityUnavailableError, RejectedTokenError } from '@/lib/session/identity';
import {
  endSession,
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

  it('replays the credential that is live now, not the one that was live one rotation ago', async () => {
    const gen0 = aSession('gen0-token');

    // A render rotates gen0 → gen1 and cannot write the cookie, so gen1 is remembered under gen0.
    rotateAtIdentity.mockImplementationOnce(slowRotation('gen1'));
    const gen1 = await refreshSession(gen0);
    expect(gen1.refreshToken).toBe('refresh-gen1');

    // A later request does carry gen1, and rotates it. gen1 is revoked at identity-service the
    // moment that commits — so the entry remembered under gen0 now names a dead credential.
    rotateAtIdentity.mockImplementationOnce(slowRotation('gen2'));
    const gen2 = await refreshSession(gen1);
    expect(gen2.refreshToken).toBe('refresh-gen2');

    // The straggler still holding gen0 — a request dispatched before the newer cookie was
    // applied — must be repaired to the *live* token. Handed gen1 it would persist a revoked
    // credential, and a sign-out on that cookie would revoke gen1 and leave gen2 alive: identity
    // ignores an unknown token, so the orphan is created silently. This is the defect
    // `verify-auth.mjs`'s "no refresh token is left alive" catches.
    const replayed = await refreshSession(gen0);
    expect(replayed.refreshToken).toBe('refresh-gen2');
    expect(replayed.accessToken).toBe('access-gen2');

    // And still without a third call: chaining must not cost a rotation.
    expect(rotateAtIdentity).toHaveBeenCalledTimes(2);
  });

  it('waits for a rotation already in progress rather than replaying the token it is revoking', async () => {
    const gen0 = aSession('gen0-token');

    rotateAtIdentity.mockImplementationOnce(slowRotation('gen1', 5));
    const gen1 = await refreshSession(gen0);

    // gen1 begins rotating, slowly enough to still be in flight below.
    rotateAtIdentity.mockImplementationOnce(slowRotation('gen2', 60));
    const gen2 = refreshSession(gen1);

    // The straggler still holding gen0 arrives mid-rotation. The cache names gen1 — the very
    // credential being revoked right now — so answering from it hands out a token that is dead on
    // arrival, and a sign-out on it would revoke nothing and orphan gen2.
    await expect(refreshSession(gen0)).resolves.toMatchObject({ refreshToken: 'refresh-gen2' });

    await expect(gen2).resolves.toMatchObject({ refreshToken: 'refresh-gen2' });
    expect(rotateAtIdentity).toHaveBeenCalledTimes(2);
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

describe('ending a session', () => {
  /**
   * The sign-out half of the same problem the replay cache exists for (D197).
   *
   * `destroySession` has one job that only this module can do correctly: name the token that is
   * actually live. Everything below is a way for the cookie to be a wrong answer to that
   * question, and every wrong answer has the same consequence — identity-service's `revoke` is
   * idempotent, so revoking a token it already destroyed returns 204, and the live credential
   * survives its full TTL with nothing left referencing it. It is `verify-auth.mjs`'s "no refresh
   * token is left alive" that notices, because nothing else can.
   */

  it('names the token that is live, not the one the cookie is holding', async () => {
    const gen0 = aSession('gen0-token');

    rotateAtIdentity.mockImplementationOnce(slowRotation('gen1'));
    const gen1 = await refreshSession(gen0);
    rotateAtIdentity.mockImplementationOnce(slowRotation('gen2'));
    await refreshSession(gen1);

    // The sign-out arrives on a cookie two generations behind — a request dispatched before the
    // newer `Set-Cookie` was applied, which is every prefetch already in flight.
    await expect(endSession(gen0.refreshToken)).resolves.toBe('refresh-gen2');

    // And without spending a rotation to find out: resolving is a read of what this process
    // already knows, not another round trip.
    expect(rotateAtIdentity).toHaveBeenCalledTimes(2);
  });

  it('waits for a rotation already in flight, because its result is what needs revoking', async () => {
    const gen0 = aSession('gen0-token');
    rotateAtIdentity.mockImplementationOnce(slowRotation('gen1', 5));
    const gen1 = await refreshSession(gen0);

    rotateAtIdentity.mockImplementationOnce(slowRotation('gen2', 60));
    const inFlight = refreshSession(gen1);

    // Signing out mid-rotation. Revoking gen1 here would revoke a token identity-service is in
    // the middle of destroying anyway, and gen2 — issued a moment later — would never be revoked.
    await expect(endSession(gen0.refreshToken)).resolves.toBe('refresh-gen2');
    await expect(inFlight).resolves.toMatchObject({ refreshToken: 'refresh-gen2' });
  });

  it('stops the chain, so a request that raced the sign-out cannot mint a replacement', async () => {
    const session = aSession('the-live-token');

    const live = await endSession(session.refreshToken);
    expect(live).toBe('the-live-token');

    /*
     * The window this closes is not narrow. Between the sign-out naming a token and the
     * revocation reaching identity-service there is a form to parse, a CSRF token to check, a
     * cookie to read and a round trip to make — and a page holds twenty prefetchable links, every
     * one of them an ordinary request that may rotate. Rotating here would put a brand-new
     * credential *after* the revocation had chosen its target.
     */
    await expect(refreshSession(session)).rejects.toBeInstanceOf(RejectedTokenError);
    expect(rotateAtIdentity).not.toHaveBeenCalled();
  });

  it('refuses every generation of the chain it walked, not only the live end', async () => {
    const gen0 = aSession('gen0-token');
    rotateAtIdentity.mockImplementationOnce(slowRotation('gen1'));
    const gen1 = await refreshSession(gen0);

    await endSession(gen0.refreshToken);

    // A straggler holding either generation is a request whose session is over. It is redirected
    // to /login rather than served a credential — the same end it reached before, minus the orphan.
    await expect(refreshSession(gen0)).rejects.toBeInstanceOf(RejectedTokenError);
    await expect(refreshSession(gen1)).rejects.toBeInstanceOf(RejectedTokenError);
    expect(rotateAtIdentity).toHaveBeenCalledTimes(1);
  });

  it('leaves another browser’s session completely alone', async () => {
    const laptop = aSession('laptop-token');
    const phone = aSession('phone-token');

    await endSession(laptop.refreshToken);

    // Keyed by token, never by user. `revokeAllForUser` is what this deliberately is not: signing
    // out of one device must not sign out of the others.
    rotateAtIdentity.mockImplementationOnce(slowRotation('phone-next'));
    await expect(refreshSession(phone)).resolves.toMatchObject({
      refreshToken: 'refresh-phone-next',
    });
  });

  it('falls back to the token it was given when this process knows of no rotation', async () => {
    // A cookie sealed by another replica, or one whose replay entry has expired. There is nothing
    // better to revoke than what the browser presented, and that is usually right.
    await expect(endSession('a-token-from-somewhere-else')).resolves.toBe(
      'a-token-from-somewhere-else',
    );
  });
});
