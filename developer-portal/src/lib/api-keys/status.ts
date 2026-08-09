import type { ApiKeySummary } from '@/lib/platform/api-keys';

/**
 * What a key's timestamps mean, in one place (M23.5).
 *
 * ── Why this is derived and not read ──────────────────────────────────────────────────
 *
 * merchant-service has no status column. `ApiKey.isActive` computes the answer from three
 * nullable instants at every verification, and this is the same computation stated once for the
 * screen — so "the dashboard says active" and "the gateway lets it through" cannot disagree.
 * Duplicating the three conditions inline at each badge is how they would.
 *
 * It lives outside `platform/api-keys.ts` because that module is `server-only` and the
 * confirmation dialogs need this too.
 */

export type KeyStatus =
  /** Authenticates. */
  | 'active'
  /** Rotated out: still authenticating, but with a deadline. */
  | 'retiring'
  /** Revoked by hand. Immediate and irreversible. */
  | 'revoked'
  /** Reached `expiresAt`, or its grace window lapsed. Nothing to undo and nothing to do. */
  | 'expired';

/**
 * @param at the moment to judge against, so the caller controls the clock rather than the module.
 *
 * The order mirrors `ApiKey.isActive` exactly: revocation wins over everything, then a hard
 * expiry, then the grace deadline. Reordering these would produce a screen that disagrees with the
 * gateway about a key that is both revoked and expired.
 */
export function keyStatus(key: ApiKeySummary, at: Date = new Date()): KeyStatus {
  const now = at.getTime();

  if (key.revokedAt) return 'revoked';
  if (key.expiresAt && Date.parse(key.expiresAt) <= now) return 'expired';
  if (key.graceExpiresAt) {
    return Date.parse(key.graceExpiresAt) <= now ? 'expired' : 'retiring';
  }
  return 'active';
}

/** Whether anything can still be done to this key. A dead key offers no actions, not disabled ones. */
export function isLive(status: KeyStatus): boolean {
  return status === 'active' || status === 'retiring';
}

/**
 * The type's own words, rather than the enum's.
 *
 * `PUBLISHABLE`/`SECRET` is the wire vocabulary; "Publishable"/"Secret" is what §4.3 calls them in
 * prose and what a developer reading Stripe-shaped docs expects to see.
 */
export function typeLabel(type: ApiKeySummary['type']): string {
  return type === 'SECRET' ? 'Secret' : 'Publishable';
}
