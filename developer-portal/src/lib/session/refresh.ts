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

/**
 * A ceiling on the set of signed-out chains, for the same reason `MAX_REPLAY_ENTRIES` bounds the
 * cache: it is keyed by token, so a busy process would otherwise accumulate one entry per
 * sign-out for as long as it runs. Evicting the oldest costs nothing — an evicted tombstone only
 * means a request racing a long-finished sign-out is answered by identity-service rejecting a
 * revoked token, which is where it ended up before any of this existed.
 */
const MAX_ENDED_CHAINS = 1000;

/**
 * A ceiling on how far the chain walk will go in one call.
 *
 * Refresh tokens are unique, so the chain cannot cycle and in practice is one or two links — the
 * generations a request in flight can be behind. This is a guard against a bug in this module
 * becoming an unbounded loop on a request path, not a limit the design expects to reach.
 */
const MAX_CHAIN_HOPS = 16;

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
 * The chains that have been signed out, by the key of every token in them.
 *
 * Insertion-ordered like the cache above, so the bound is enforced the same way.
 */
const ended = new Set<string>();

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

function markEnded(key: string): void {
  if (ended.size >= MAX_ENDED_CHAINS) {
    const oldest = ended.values().next();
    if (!oldest.done) ended.delete(oldest.value);
  }
  ended.add(key);
}

/**
 * Walks forward from a token to the credential that is actually live, through every rotation this
 * process knows about — completed or still in flight.
 *
 * ── Why one hop is not enough ─────────────────────────────────────────────────────────
 *
 * A cached rotation names the live credential only until that credential is *itself* rotated.
 * Returning the first entry found therefore answers a two-generation-old cookie with a token
 * identity-service revoked some time ago. That is not a stale read that corrects itself: the
 * caller persists what it was given, so the cookie now names a dead token while the live one has
 * nothing referencing it. `destroySession` then revokes the dead one — and because
 * `RefreshTokenService.revoke` is idempotent and ignores an unknown token, identity answers 204
 * and the live token is orphaned **silently**, to live out its TTL. `verify-auth.mjs`'s "no
 * refresh token is left alive" is the assertion that catches it.
 *
 * A cookie two generations behind is not exotic. Every response carrying a rotation is a
 * `Set-Cookie` the browser may not have applied yet, so any request already in flight — a
 * prefetch, a parallel RSC fetch, the sign-out POST itself — arrives holding whatever generation
 * was current when it was dispatched. Under load that is routinely not the newest.
 *
 * ── Why `inFlight` is part of the walk ────────────────────────────────────────────────
 *
 * Checking only completed rotations leaves the same defect in a narrower window: a caller can
 * read an entry naming token X in the instant another request is *already rotating* X, and be
 * handed a credential that is dead by the time it is persisted. Following into the pending
 * promise closes it — the rotation in progress produces the successor, so awaiting it is the only
 * way to name the token that will still be live afterwards.
 *
 * ── What bounds it ────────────────────────────────────────────────────────────────────
 *
 * Each link keeps its own `REPLAY_TTL_MS`, set when that rotation completed, and entries are
 * never rewritten. So the window in which any one superseded cookie is still honoured stays
 * bounded from its own rotation rather than being extended each time the session rotates again.
 * An expired or evicted link simply ends the walk: the caller rotates what it holds, and a dead
 * token then fails as it always has.
 *
 * @returns the newest rotation reachable from the one given — itself, if this process knows of
 *          nothing that replaced it.
 */
async function liveEndOf(rotation: Rotation): Promise<Rotation> {
  let latest = rotation;

  for (let hop = 0; hop < MAX_CHAIN_HOPS; hop++) {
    const key = keyFor(latest.refreshToken);

    // A rotation of this token is in progress. Its result is the successor, so wait for it rather
    // than returning the credential it is in the middle of revoking.
    const pending = inFlight.get(key);
    if (pending) {
      latest = await pending;
      continue;
    }

    const done = readReplayable(key, Date.now());
    if (!done) break;
    latest = done;
  }

  return latest;
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

/**
 * Ends a session's refresh-token chain and names the credential that has to be revoked (D197).
 *
 * ── Why sign-out has to come through here ─────────────────────────────────────────────
 *
 * `destroySession` used to revoke whatever token the request's cookie happened to carry. That is
 * the one thing in this design that is *not* a reliable name for the session's credential, and
 * this module exists because it is not: a rotation performed during a render cannot be persisted,
 * a `Set-Cookie` the browser has not applied yet leaves every request already in flight holding
 * the previous generation, and both are ordinary rather than exotic. A sign-out reading the
 * cookie directly therefore revokes a token identity-service already destroyed. `revoke` is
 * idempotent and ignores an unknown token, so it answers 204 — and the credential that *is* live
 * survives, unreferenced, for the whole seven days of its TTL. Nothing reports it; the browser
 * has been signed out and looks it.
 *
 * ── Resolving the chain is only half of it ────────────────────────────────────────────
 *
 * Walking to the live end fixes a cookie that is behind. It does not fix a cookie that is about
 * to *become* behind, and there is real time between the two: parsing the sign-out form, checking
 * its CSRF token, reading the cookie, and the round trip to identity-service. A page holds twenty
 * prefetchable links, so a request rotating inside that window is not a coincidence to be
 * tolerated — it is the common case under load, and it puts the rotation *after* the revocation
 * chose its target. Precisely the orphan again, one generation further along.
 *
 * So the walk marks each link as it passes, and `rotate` refuses a marked chain before it does
 * anything else. Once this function has looked at a token, no rotation of it can begin, and the
 * token returned is therefore still the live one when the caller revokes it. Marking happens
 * *before* each `await` for the same reason the mutex is registered before its first: a gap
 * between deciding and recording is a gap something else can rotate in.
 *
 * A rotation already under way is waited for rather than raced. It will succeed — identity has
 * the token — so its result is the credential that must be revoked, and abandoning it would
 * orphan exactly what this exists to prevent.
 *
 * ── What it deliberately does not do ──────────────────────────────────────────────────
 *
 * It does not touch the user's other sessions. Every entry here is keyed by a token in *this*
 * chain, and a second browser holds a chain that shares no token with it, so signing out of one
 * device leaves the others exactly as they were — which is what `RefreshTokenService.revoke`
 * promises and what `revokeAllForUser`, deliberately not used here, would break.
 *
 * @returns the token to revoke: the live end of the chain if this process knows of one, and
 *          otherwise the token it was given, which is then the best name available.
 */
export async function endSession(refreshToken: string): Promise<string> {
  let live = refreshToken;

  for (let hop = 0; hop < MAX_CHAIN_HOPS; hop++) {
    const key = keyFor(live);
    markEnded(key);

    const pending = inFlight.get(key);
    if (pending) {
      // Its result supersedes this token, so it is the one that needs revoking. Awaiting is safe:
      // the chain is already closed behind us, so nothing new can start while we wait.
      live = (await pending).refreshToken;
      continue;
    }

    const done = readReplayable(key, Date.now());
    if (!done) break;
    live = done.refreshToken;
  }

  // The end of the walk is a token no rotation has consumed yet, so it needs a tombstone of its
  // own — it is the one a racing request is most likely to be holding.
  markEnded(keyFor(live));
  return live;
}

async function rotate(refreshToken: string): Promise<Rotation> {
  const key = keyFor(refreshToken);

  /*
   * Checked first, and synchronously, because this is the half of `endSession` that actually
   * closes the race. A request already queued when the user signed out arrives holding a token
   * from a chain that is over; rotating it would mint a credential *after* the revocation chose
   * what to revoke, and nothing would ever revoke the replacement.
   *
   * `RejectedTokenError` is the honest classification and the one the callers already handle:
   * the middleware clears the cookie and redirects to `/login`, and `callAs` reports the 401 it
   * already had. That is the correct end for a request that raced its own sign-out, and it is
   * where such a request ended up anyway once identity-service saw the revoked token — the
   * difference is that now no orphan is created on the way there.
   */
  if (ended.has(key)) throw new RejectedTokenError('the session this token belongs to has ended');

  /*
   * Both lookups are synchronous and both are deliberately before any `await`. A caller that
   * yields before reaching the registration below has left a window in which every other caller
   * also finds nothing — which is ten rotations instead of one, the exact failure the mutex
   * exists to prevent. Only once something *is* found is it safe to await, and `liveEndOf` then
   * walks from there to the credential that is actually live.
   */
  const pending = inFlight.get(key);
  if (pending) return liveEndOf(await pending);

  const alreadyDone = readReplayable(key, Date.now());
  if (alreadyDone) return liveEndOf(alreadyDone);

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
    // One entry per consumed token. Each is a link, and `liveEndOf` walks them, so an entry is
    // never rewritten — which is what keeps every link's expiry bounded from its own rotation.
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
  ended.clear();
}

/** Test seam: how many rotations are in flight, how many results are replayable, how many ended. */
export function refreshCoordinatorStateForTesting(): {
  inFlight: number;
  replayable: number;
  ended: number;
} {
  return { inFlight: inFlight.size, replayable: replayable.size, ended: ended.size };
}
