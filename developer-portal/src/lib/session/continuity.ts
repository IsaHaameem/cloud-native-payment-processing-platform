import 'server-only';

import { processScoped } from './process-state';
import { type Session } from './session';

/**
 * Carrying a session's durable facts across a rotation it did not take part in (M23.2, D198).
 *
 * ── The defect this exists for ────────────────────────────────────────────────────────
 *
 * A refresh re-seals the *whole* session, not just the credentials. The middleware decodes the
 * cookie the request arrived with, rotates the token, and writes `{...that session, new tokens}`
 * back. That is correct for every field except the ones a user can change mid-session, because
 * the snapshot it re-seals is only as new as the request that carried it.
 *
 * So: a user finishes onboarding, and `onboarding/actions.ts` writes a cookie carrying the new
 * `merchantId`. Meanwhile a prefetch dispatched *before* that response — an ordinary request, and
 * under a short access-token TTL one that also refreshes — is still in flight. It arrives holding
 * the pre-onboarding snapshot, rotates, and re-seals it. Its `Set-Cookie` lands second, so the
 * browser keeps the older state and `merchantId` is gone. `/onboarding` then shows the setup form
 * to a user who already has a merchant, because `onboarding/page.tsx` branches on exactly that
 * field, and the dashboard guard sends them straight back — the redirect loop both guards exist
 * to prevent, reached from the one direction neither of them checks.
 *
 * A page holds twenty prefetchable links, so this is not a coincidence to be tolerated. It is the
 * common case under load, which is why `verify-public.mjs`'s new-user journey saw it and a
 * developer clicking through by hand did not.
 *
 * ── Why the fix belongs here rather than in the guards ────────────────────────────────
 *
 * `onboarding/page.tsx` already explains why it cannot repair this itself: a Server Component
 * cannot write a cookie, so re-asking merchant-service would produce an answer it could do
 * nothing with. The middleware *can* write cookies — but making it ask the platform would put a
 * network call on every request of every merchant-less user, which is the one thing the
 * middleware is documented not to do.
 *
 * So neither guard is the right place. The right place is the re-seal itself: a rotation must not
 * be allowed to publish a fact older than one this process has already written.
 *
 * ── Why `merchantId` alone, and why keying by user is sound ───────────────────────────
 *
 * `merchantId` is **monotonic and owned by the user, not by the browser session**. A user has at
 * most one merchant; the field goes from absent to set exactly once and never changes or clears.
 * Two properties follow, and together they are what make this safe without any ordering
 * machinery:
 *
 * 1. Keying by `userId` cannot leak anything between sessions, because every session of that user
 *    is entitled to precisely this value. Filling it into a second browser that onboarded
 *    elsewhere is not a leak but a repair — that browser is otherwise stuck on `/onboarding`.
 * 2. "Newer" needs no timestamp. A set value is always newer than an absent one, so the merge is
 *    a one-way upgrade and a stale writer can never win.
 *
 * It is also not an authorization decision. The platform enforces merchant ownership against the
 * access token — a session naming a merchant it does not own is answered 403 (M23.0) — so this
 * chooses *which merchant the portal asks about*, never what it is allowed to see. Since the only
 * value that can ever be filled in is the one recorded for that same `userId`, there is no path
 * by which one user's session can acquire another's merchant.
 *
 * `mode` is deliberately **not** carried this way. It is per-browser-session rather than per-user
 * and it is not monotonic, so it would need a session-scoped key and a real ordering rule; and
 * getting that wrong means a session inheriting `live` without the user asking, which is the
 * exact confusion D184 exists to prevent. It has the same defect and wants the same kind of fix,
 * but not this key.
 *
 * ── Scope: one process ────────────────────────────────────────────────────────────────
 *
 * Module state, like the refresh coordinator's, and weakening the same way across replicas: a
 * rotation served by a replica that never saw the onboarding write cannot repair the cookie, and
 * that request falls back to today's behaviour — one stale render, then `onboarding/actions.ts`'s
 * 409 recovery reseals it correctly. Degraded, never wrong. See §14 of PROJECT_CONTEXT_2.md.
 */

/**
 * A ceiling, for the same reason the refresh coordinator has one: this is keyed by user, so a
 * long-lived process serving many merchants would otherwise grow it without bound. Eviction is
 * oldest-first and costs only the repair — an evicted user's stale re-seal behaves as it did
 * before this existed.
 */
const MAX_TRACKED_USERS = 1000;

/**
 * `userId` → the merchant that user is known to own.
 *
 * Process-scoped, and that is load-bearing rather than incidental: the write happens in a Server
 * Action and the read happens in middleware, which Next.js compiles as separate bundles with
 * separate module registries. A module-level `Map` here is two maps, and the one the middleware
 * reads is always empty — the fix would be inert. See `process-state.ts`.
 */
const merchantOfUser = processScoped(
  'session/continuity:merchantOfUser',
  () => new Map<string, string>(),
);

/**
 * Records what an authoritative write established.
 *
 * Called from `persistSession` and nowhere else, and that distinction is the whole mechanism:
 * `persistSession` is how the three writers that *decide* session state commit it — sign-in,
 * onboarding, the mode switch — while a rotation re-seal only ever carries state forward. Because
 * nothing but a decision is recorded here, what is recorded is by construction at least as new as
 * anything a re-seal is holding, and {@link carryForwardDurableFacts} can take it unconditionally.
 */
export function rememberDurableFacts(session: Session): void {
  if (session.merchantId === undefined) return;
  if (merchantOfUser.get(session.userId) === session.merchantId) return;

  if (merchantOfUser.size >= MAX_TRACKED_USERS) {
    // Map iteration is insertion-ordered, so the first key is the oldest.
    const oldest = merchantOfUser.keys().next();
    if (!oldest.done) merchantOfUser.delete(oldest.value);
  }
  merchantOfUser.set(session.userId, session.merchantId);
}

/**
 * Restores the durable facts a rotating request may be too old to know about.
 *
 * Call this on any session that is about to be re-sealed *because its token rotated* rather than
 * because something decided its contents — the middleware and the API client's retry. Never call
 * it on an authoritative write: those carry the decision, and reconciling one against the record
 * it is itself about to update is how a deliberate change gets reverted.
 *
 * @returns the session, with `merchantId` filled in if this process knows one and the session
 *          does not. Returned unchanged in every other case, including the one that matters most
 *          — a session that already names a merchant is never second-guessed.
 */
export function carryForwardDurableFacts(session: Session): Session {
  if (session.merchantId !== undefined) return session;

  const known = merchantOfUser.get(session.userId);
  if (known === undefined) return session;

  return { ...session, merchantId: known };
}

/** Test seam. Never called by application code. */
export function resetSessionContinuityForTesting(): void {
  merchantOfUser.clear();
}

/** Test seam: how many users have a recorded merchant. */
export function sessionContinuityStateForTesting(): { tracked: number } {
  return { tracked: merchantOfUser.size };
}
