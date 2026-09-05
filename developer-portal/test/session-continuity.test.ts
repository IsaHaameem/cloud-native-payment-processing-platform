import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  carryForwardDurableFacts,
  rememberDurableFacts,
  resetSessionContinuityForTesting,
  sessionContinuityStateForTesting,
} from '@/lib/session/continuity';
import { SESSION_VERSION, type Session } from '@/lib/session/session';

/**
 * The onboarding race (M23.2a) — a rotation publishing a session older than one already written.
 *
 * ── What went wrong, in the order it happens ──────────────────────────────────────────
 *
 * A refresh re-seals the whole session, not only its credentials, from whatever snapshot the
 * rotating request arrived with. So a prefetch dispatched *before* onboarding's `Set-Cookie` and
 * completing *after* it re-published the pre-onboarding session, and the browser — applying
 * `Set-Cookie` in arrival order — kept the older one. `merchantId` was gone, `onboarding/page.tsx`
 * showed the setup form to a user who already had a merchant, and the dashboard guard sent them
 * back to it.
 *
 * These are unit tests of the reconciliation rather than of the browser, because the property is
 * about *ordering between two writers* and a browser can only ever sample one interleaving.
 * `verify-public.mjs` walks the journey end to end; this pins the rule the journey depends on.
 */

const USER_ID = '11111111-2222-3333-4444-555555555555';
const MERCHANT_ID = '66666666-7777-8888-9999-000000000000';

function aSession(overrides: Partial<Session> = {}): Session {
  const now = Date.now();
  return {
    version: SESSION_VERSION,
    userId: USER_ID,
    email: 'grace@example.com',
    roles: ['USER'],
    accessToken: 'access.token.one',
    accessExpiresAt: now + 60_000,
    refreshToken: 'refresh-one',
    refreshExpiresAt: now + 7 * 24 * 60 * 60 * 1000,
    merchantId: undefined,
    mode: 'test',
    createdAt: now,
    ...overrides,
  };
}

beforeEach(() => {
  resetSessionContinuityForTesting();
});

afterEach(() => {
  resetSessionContinuityForTesting();
});

describe('carrying durable facts across a rotation', () => {
  it('keeps the merchant a later request established, when an earlier one finishes last', () => {
    // 1 — signed in, no merchant. This is the snapshot the prefetch is already holding.
    const beforeOnboarding = aSession();
    expect(beforeOnboarding.merchantId).toBeUndefined();

    // 2 — onboarding completes and commits the decision, exactly as `onboarding/actions.ts` does.
    rememberDurableFacts({ ...beforeOnboarding, merchantId: MERCHANT_ID });

    // 3 — the straggler finally rotates. Its session is the *old* one, and re-sealing it verbatim
    //     is what published a cookie with no merchant over the one that had it.
    const rotated = {
      ...beforeOnboarding,
      accessToken: 'access.token.two',
      refreshToken: 'refresh-two',
    };
    const resealed = carryForwardDurableFacts(rotated);

    // 4 — the newer fact survives the older writer.
    expect(resealed.merchantId).toBe(MERCHANT_ID);

    // 5 — and this is precisely the predicate `onboarding/page.tsx` branches on, so the finished
    //     merchant is redirected to the dashboard instead of being shown the setup form again.
    expect(resealed.merchantId !== undefined).toBe(true);

    // The rotation is still carried through; reconciling must not undo the refresh.
    expect(resealed.accessToken).toBe('access.token.two');
    expect(resealed.refreshToken).toBe('refresh-two');
  });

  it('does not need the straggler to be the same generation, only the same user', () => {
    rememberDurableFacts(aSession({ merchantId: MERCHANT_ID }));

    // A request several rotations behind, which is what a prefetch under load actually is.
    const veryStale = aSession({ refreshToken: 'refresh-from-four-rotations-ago' });
    expect(carryForwardDurableFacts(veryStale).merchantId).toBe(MERCHANT_ID);
  });

  it('never second-guesses a session that already names a merchant', () => {
    rememberDurableFacts(aSession({ merchantId: MERCHANT_ID }));

    // The upgrade is one-way: absent → known. A session carrying a value is returned untouched,
    // so this can only ever repair, never overwrite.
    const other = aSession({ merchantId: 'a-different-merchant' });
    expect(carryForwardDurableFacts(other)).toBe(other);
  });

  it('leaves a genuinely merchant-less user on onboarding', () => {
    // Nothing recorded: a user who has not onboarded must still reach the setup form. Inventing a
    // merchant here would be worse than the bug — it would route them past the step they need.
    const fresh = aSession();
    expect(carryForwardDurableFacts(fresh)).toBe(fresh);
    expect(carryForwardDurableFacts(fresh).merchantId).toBeUndefined();
  });

  it('never hands one user another user’s merchant', () => {
    rememberDurableFacts(aSession({ merchantId: MERCHANT_ID }));

    // Keyed by user, which is what makes keying by user rather than by session safe at all: the
    // only value a session can acquire is the one recorded for its own `userId`.
    const somebodyElse = aSession({ userId: '99999999-8888-7777-6666-555555555555' });
    expect(carryForwardDurableFacts(somebodyElse).merchantId).toBeUndefined();
  });

  it('records nothing for a session that has no merchant to record', () => {
    rememberDurableFacts(aSession());
    expect(sessionContinuityStateForTesting().tracked).toBe(0);
  });

  it('follows the user when their merchant is established in another browser', () => {
    // Two sessions, one account. The second browser signed in before onboarding happened
    // elsewhere, so its cookie names no merchant and its guard would strand it on /onboarding.
    // Filling in the value it is already entitled to is a repair, not a leak.
    rememberDurableFacts(aSession({ createdAt: 1, merchantId: MERCHANT_ID }));

    const otherBrowser = aSession({ createdAt: 2 });
    expect(carryForwardDurableFacts(otherBrowser).merchantId).toBe(MERCHANT_ID);
  });

  it('does not carry `mode`, which is per-session and not this key’s to move', () => {
    rememberDurableFacts(aSession({ merchantId: MERCHANT_ID, mode: 'live' }));

    // A session inheriting `live` because another browser chose it is the confusion D184 exists
    // to prevent. `mode` needs a session-scoped key and an ordering rule; `merchantId` needs
    // neither, and conflating them is how the safe fix becomes an unsafe one.
    expect(carryForwardDurableFacts(aSession()).mode).toBe('test');
  });
});
