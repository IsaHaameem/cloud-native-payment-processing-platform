import 'server-only';

import { RejectedTokenError, refresh as rotateAtIdentity } from './identity';
import { type Session } from './session';

/**
 * Coordinated refresh (M23.2, D197). The most load-bearing module in the milestone.
 *
 * ── What the backend actually does, verified rather than assumed ──────────────────────
 *
 * `RefreshTokenService.rotate` (identity-service) revokes the presented token and issues a new
 * one inside one transaction. Three consequences, all measured from the code rather than hoped
 * for:
 *
 * 1. **Rotation is single-use.** The presented token is dead the moment the transaction commits.
 * 2. **`RefreshToken` has no `@Version`.** Two concurrent rotations of the same token therefore
 *    both *succeed* — the row lock serialises the `UPDATE`, and with no optimistic-locking
 *    column neither transaction fails. Identity mints two live refresh tokens; the session can
 *    only keep one, and the other survives, unreferenced, until its TTL.
 * 3. **Reuse does not invalidate the family.** A replayed token throws `InvalidTokenException`
 *    and nothing else happens, so the orphan from (2) is a live credential nobody is watching.
 *
 * The failure that follows is the one this module exists to prevent: a third request arriving
 * after the first rotation commits presents a token that is now revoked, receives 401, and — with
 * no coordination — signs the user out in the middle of loading a page.
 *
 * ── Two mechanisms, because one is not enough ─────────────────────────────────────────
 *
 * **The mutex** collapses concurrent refreshes of the same token into a single in-flight
 * promise. Ten parallel requests against an expired access token produce exactly one call to
 * identity-service and ten callers awaiting its result. That is the property tested in
 * `refresh.test.ts`.
 *
 * **The replay cache** is the part that is easy to leave out and expensive to omit. Next.js
 * Server Components cannot set cookies, so a rotation triggered during a render cannot be
 * persisted. Without a cache, the next request presents the token that rotation already revoked
 * and the user is signed out — a logout caused entirely by *where* the refresh happened. With
 * it, that request finds the completed result under the old token's key, returns it, and
 * persists it from a context that is allowed to. The window is short and bounded, and it is the
 * difference between a design that survives its own architecture and one that does not.
 *
 * Keyed by a hash of the refresh token so the map cannot become a place tokens are read from,
 * and so a heap dump does not enumerate live credentials.
 *
 * ── Scope: one process ────────────────────────────────────────────────────────────────
 *
 * Module state, so the guarantee holds across every request served by one Node process — which
 * is what `docker-compose.yml` runs and what a single-replica deployment runs.
 *
 * With N replicas the guarantee weakens to per-replica: two replicas can rotate the same token
 * concurrently, and by (2) above both succeed, so no user is signed out — one replica simply
 * holds a refresh token the other has orphaned, and the next request through the losing replica
 * finds its token revoked. That request *would* sign the user out, and the replay cache does not
 * help because the entry lives in the other process. This is recorded as known debt rather than
 * papered over: fixing it needs shared state (the Redis the platform already runs) or sticky
 * sessions, and both are deployment decisions rather than portal code. See §14 of
 * PROJECT_CONTEXT_2.md.
 */

/**
 * How long a completed rotation stays replayable.
 *
 * Long enough to cover a render that could not persist plus the next request, short enough that
 * a leaked cookie cannot be exchanged for credentials indefinitely. Thirty seconds is roughly
 * two orders of magnitude more than the window it covers.
 */
const REPLAY_TTL_MS = 30_000;

/**
 * A ceiling on the cache, so a burst of distinct sessions cannot grow it without bound. Entries
 * are evicted oldest-first; an evicted entry costs one extra rotation, never correctness.
 */
const MAX_REPLAY_ENTRIES = 1000;

interface Rotation {
  readonly accessToken: string;
  readonly accessExpiresAt: number;
  readonly refreshToken: string;
}

interface CachedRotation {
  readonly rotation: Rotation;
  readonly expiresAt: number;
}

/** Keyed by `hash(refreshToken)`. */
const inFlight = new Map<string, Promise<Rotation>>();
const replayable = new Map<string, CachedRotation>();

/**
 * FNV-1a over the token. Not a security boundary — it keeps raw refresh tokens out of a
 * long-lived map, so the collision resistance that matters is "two different tokens in the same
 * 30-second window", not "an attacker cannot forge a preimage". A cryptographic hash here would
 * make every key derivation async for no gain.
 */
function keyFor(refreshToken: string): string {
  let hash = 0x811c9dc5;
  for (let i = 0; i < refreshToken.length; i++) {
    hash ^= refreshToken.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return `${hash.toString(16)}:${refreshToken.length}`;
}

function readReplayable(key: string, now: number): Rotation | undefined {
  const cached = replayable.get(key);
  if (!cached) return undefined;
  if (cached.expiresAt <= now) {
    replayable.delete(key);
    return undefined;
  }
  return cached.rotation;
}

function remember(key: string, rotation: Rotation, now: number): void {
  if (replayable.size >= MAX_REPLAY_ENTRIES) {
    // Map iteration is insertion-ordered, so the first key is the oldest.
    const oldest = replayable.keys().next();
    if (!oldest.done) replayable.delete(oldest.value);
  }
  replayable.set(key, { rotation, expiresAt: now + REPLAY_TTL_MS });
}

/**
 * Refreshes a session's credentials, at most once per refresh token.
 *
 * @returns a new `Session` carrying the rotated credentials. The caller is responsible for
 *          persisting it — and for the fact that it may not be able to, which is what the replay
 *          cache above accounts for.
 * @throws {RejectedTokenError} the token is genuinely dead; the session is over.
 * @throws {IdentityUnavailableError} the platform is unwell; the session is *not* over.
 */
export async function refreshSession(session: Session): Promise<Session> {
  const rotation = await rotate(session.refreshToken);
  return {
    ...session,
    accessToken: rotation.accessToken,
    accessExpiresAt: rotation.accessExpiresAt,
    refreshToken: rotation.refreshToken,
  };
}

async function rotate(refreshToken: string): Promise<Rotation> {
  const key = keyFor(refreshToken);
  const now = Date.now();

  const alreadyDone = readReplayable(key, now);
  if (alreadyDone) return alreadyDone;

  const pending = inFlight.get(key);
  if (pending) return pending;

  const attempt = (async () => {
    const issued = await rotateAtIdentity(refreshToken);
    return {
      accessToken: issued.accessToken,
      accessExpiresAt: issued.accessExpiresAt,
      refreshToken: issued.refreshToken,
    };
  })();

  // Registered before the first `await` resumes anywhere else, so a second caller entering this
  // function while the request is in flight finds the promise rather than starting a second one.
  inFlight.set(key, attempt);

  try {
    const rotation = await attempt;
    remember(key, rotation, Date.now());
    return rotation;
  } catch (error) {
    // A rejected token is not cached: it may have been rejected precisely because another
    // process already rotated it, and caching the failure would make that permanent for the
    // life of the entry.
    if (error instanceof RejectedTokenError) throw error;
    throw error;
  } finally {
    // Only if it is still ours. A later call that replaced the entry must not have it deleted
    // out from under it by an earlier one finishing late.
    if (inFlight.get(key) === attempt) inFlight.delete(key);
  }
}

/** Test seam. Never called by application code. */
export function resetRefreshCoordinatorForTesting(): void {
  inFlight.clear();
  replayable.clear();
}

/** Test seam: how many rotations are in flight, and how many results are replayable. */
export function refreshCoordinatorStateForTesting(): { inFlight: number; replayable: number } {
  return { inFlight: inFlight.size, replayable: replayable.size };
}
