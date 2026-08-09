import 'server-only';

/**
 * Per-address throttling of password-reset requests (M23.2b).
 *
 * ── A different policy from the login throttle, which is why it is a different module ─
 *
 * `login-throttle.ts` counts **failures** and is cleared by a success, because the attack there
 * is guessing one account's password. Nothing about a reset request can fail from the caller's
 * point of view — the platform answers 202 for every address, existing or not — so there are no
 * failures to count. What is being limited here is the *volume of email* one address can be made
 * to receive, and the resource being protected is the mailbox of someone who did not ask.
 *
 * So this counts every request, and a success does not clear it. Sharing the login module's map
 * with a key prefix would have put two policies behind one set of semantics, and the next person
 * to change `recordSuccess` would have changed both.
 *
 * ── The throttle must not become the oracle the endpoint refuses to be ────────────────
 *
 * This is the part worth being careful about. identity-service goes out of its way not to reveal
 * whether an address has an account; a throttle that answered differently for a throttled request
 * would hand that back in a different shape — an attacker could learn nothing from one request,
 * then learn something from the eleventh.
 *
 * It cannot, because of how the caller uses it: the page renders **the same success state no
 * matter what**. A throttled request simply does not reach the platform. The user sees what they
 * would have seen anyway, and the only observable difference is an email that does not arrive —
 * for an address whose owner is already being flooded, which is the outcome intended.
 *
 * ── Scope: one process, as with every other limiter here ──────────────────────────────
 *
 * Module state, so with multiple replicas the allowance multiplies by the replica count. That is
 * a weaker limit rather than no limit, it is the same debt already recorded for the refresh
 * coordinator and the login throttle (§14), and the gateway's own limiter still stands behind it.
 */

/** Requests allowed per address inside the window. Generous: a real user retries a few times. */
const MAX_REQUESTS = 5;

/** An hour, matching identity-service's `password-reset-ttl` — one window, one live token. */
const WINDOW_MS = 60 * 60 * 1000;

/** Bound on the map, so a flood of distinct addresses cannot grow it without limit. */
const MAX_TRACKED = 10_000;

interface Requests {
  count: number;
  expiresAt: number;
}

const requests = new Map<string, Requests>();

function prune(now: number): void {
  for (const [key, value] of requests) {
    if (value.expiresAt <= now) requests.delete(key);
  }
  if (requests.size >= MAX_TRACKED) {
    const oldest = requests.keys().next();
    if (!oldest.done) requests.delete(oldest.value);
  }
}

/**
 * Records a reset request and reports whether it may proceed to the platform.
 *
 * @returns `true` if the request is within the allowance. The caller shows the same screen
 *          either way — see the note above about why that is what keeps this from leaking.
 */
export function consumeResetAllowance(email: string, now: number = Date.now()): boolean {
  prune(now);

  const record = requests.get(email);
  if (!record || record.expiresAt <= now) {
    requests.set(email, { count: 1, expiresAt: now + WINDOW_MS });
    return true;
  }

  if (record.count >= MAX_REQUESTS) return false;

  record.count += 1;
  // The window does *not* slide here, unlike the login throttle's. There the sliding window stops
  // a patient attacker pacing guesses forever; here it would mean a user who asks for a link once
  // an hour, legitimately, eventually locks themselves out of ever getting one.
  return true;
}

/** Test seam. Never called by application code. */
export function resetPasswordResetThrottleForTesting(): void {
  requests.clear();
}

export const RESET_THROTTLE_LIMITS = { maxRequests: MAX_REQUESTS, windowMs: WINDOW_MS } as const;
