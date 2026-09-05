import 'server-only';

/**
 * Module state that is actually one thing per process (M23.2, D197/D198).
 *
 * ── The assumption that turned out to be false ────────────────────────────────────────
 *
 * `refresh.ts` and `continuity.ts` are built on in-process coordination: one mutex, one replay
 * cache, one record of what a user has onboarded. A module-level `Map` looks like exactly that,
 * and inside a single bundle it is.
 *
 * Next.js does not build one bundle. Middleware is compiled as its own entry, separate from the
 * app server, and the two do not share a module registry even when both run on the Node runtime
 * in one process. So a `Map` declared at module scope is instantiated **twice**, and which copy
 * a call reaches depends on where it runs: a rotation in `middleware.ts` reaches one, and
 * `destroySession` in the `/logout` Route Handler or `callAs` in a Server Component reaches the
 * other. Measured, not assumed — with an instance tag logged on both sides, the middleware copy
 * held fifty-seven rotations while the Route Handler's copy reported an empty cache on the same
 * request.
 *
 * That is not a small discrepancy. It made `endSession`'s chain walk read an empty history and
 * its tombstones land where nothing consults them, and it made the onboarding repair in
 * `continuity.ts` a record written by a Server Action that the middleware could never see. Both
 * were correct code coordinating with the wrong copy of themselves.
 *
 * ── Why `globalThis` is the right answer here rather than a smell ─────────────────────
 *
 * The bundles are separate module registries but they are one process, and `globalThis` is the
 * one thing they genuinely share. Keyed with `Symbol.for`, so the entry is looked up in the
 * runtime-wide symbol registry rather than by a string property anything else might collide with
 * or enumerate.
 *
 * This does not widen the scope of the guarantee — it restores the scope that was intended. The
 * state is still per process, still lost on restart, and still per replica; §14 of
 * PROJECT_CONTEXT_2.md's note about N replicas is unchanged. What changes is that "per process"
 * is now true, where it previously meant "per bundle, and which one depends on where you stand".
 *
 * It also survives development's hot reloading, which re-evaluates modules and would otherwise
 * discard a replay cache and a mutex on every edit — a second reason the module-scoped version
 * was quietly weaker than it read.
 */

const REGISTRY = Symbol.for('paymentflow.developer-portal.process-state');

type Registry = Map<string, unknown>;

function registry(): Registry {
  const host = globalThis as typeof globalThis & { [REGISTRY]?: Registry };
  host[REGISTRY] ??= new Map<string, unknown>();
  return host[REGISTRY];
}

/**
 * @param name a stable key. Namespaced by module, because this registry is shared by every
 *             bundle in the process and a collision would silently alias two pieces of state.
 * @param create called at most once per process, the first time any bundle asks.
 * @returns the one instance of this state for this process.
 */
export function processScoped<T>(name: string, create: () => T): T {
  const store = registry();
  if (!store.has(name)) store.set(name, create());
  return store.get(name) as T;
}
