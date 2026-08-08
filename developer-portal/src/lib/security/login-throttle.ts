import 'server-only';

/**
 * Per-account login throttling (M23.2, D200).
 *
 * ── Why the portal has to do this at all ──────────────────────────────────────────────
 *
 * The gateway rate-limits `/api/v1/auth/**` by remote address
 * (`RateLimiterConfig.clientIp`, which reads the socket address and deliberately does not trust
 * `X-Forwarded-For`). Every portal login arrives from the portal's own address, so all users
 * share one bucket — and identity-service has no per-account lockout of its own:
 * `AuthService.login` counts a metric on failure and nothing more.
 *
 * The result is that credential stuffing against one account is limited only by a quota sized
 * for the whole portal. This closes that specific gap at the only layer that can see which
 * account is being attacked.
 *
 * It is **not** a replacement for the gateway's limiter, which still protects the platform from
 * the portal. It is the missing per-identity dimension.
 *
 * ── Counted per account, not per address ──────────────────────────────────────────────
 *
 * Keyed by the normalised email. Keying by client address instead would be trivially defeated
 * from a botnet and would also punish a shared office NAT for one person's typo. The attack this
 * stops is "many guesses at one account"; the key therefore has to be the account.
 *
 * The consequence is honest and worth stating: an attacker who knows an address can lock its
 * owner out for the window by failing on purpose. That is why the window is minutes rather than
 * hours, and why the counter is cleared by a *successful* login — the real owner, who knows the
 * password, gets straight back in.
 *
 * ── Scope: one process, and deliberately so ───────────────────────────────────────────
 *
 * Module state. With multiple replicas an attacker gets the allowance times the replica count,
 * which is a weaker limit rather than no limit. Sharing it needs Redis — the same deployment
 * decision as the refresh coordinator's, and recorded as the same debt.
 */

/** Failures allowed inside the window before the account stops being tried. */
const MAX_ATTEMPTS = 8;

/** How long the count is remembered, and how long a lockout lasts. */
const WINDOW_MS = 5 * 60 * 1000;

/** Bound on the map, so a flood of distinct addresses cannot grow it without limit. */
const MAX_TRACKED = 10_000;

interface Attempts {
  count: number;
  /** When the window — and any lockout — ends. */
  expiresAt: number;
}

const attempts = new Map<string, Attempts>();

function prune(now: number): void {
  for (const [key, value] of attempts) {
    if (value.expiresAt <= now) attempts.delete(key);
  }
  if (attempts.size >= MAX_TRACKED) {
    const oldest = attempts.keys().next();
    if (!oldest.done) attempts.delete(oldest.value);
  }
}

/**
 * @returns `true` if this account has spent its allowance and should not be tried.
 *
 * Checked *before* the call to identity-service, so a throttled attempt costs nothing downstream
 * — which is the point: the gateway's shared bucket must not be spent on an attack the portal
 * has already recognised.
 */
export function isThrottled(email: string, now: number = Date.now()): boolean {
  const record = attempts.get(email);
  if (!record) return false;
  if (record.expiresAt <= now) {
    attempts.delete(email);
    return false;
  }
  return record.count >= MAX_ATTEMPTS;
}

export function recordFailure(email: string, now: number = Date.now()): void {
  prune(now);
  const record = attempts.get(email);
  if (!record || record.expiresAt <= now) {
    attempts.set(email, { count: 1, expiresAt: now + WINDOW_MS });
    return;
  }
  record.count += 1;
  // The window slides on each failure, so a patient attacker cannot pace guesses to sit just
  // under the limit forever — sustained failure keeps the account throttled.
  record.expiresAt = now + WINDOW_MS;
}

export function recordSuccess(email: string): void {
  attempts.delete(email);
}

/** Test seam. Never called by application code. */
export function resetLoginThrottleForTesting(): void {
  attempts.clear();
}

export const LOGIN_THROTTLE_LIMITS = { maxAttempts: MAX_ATTEMPTS, windowMs: WINDOW_MS } as const;
