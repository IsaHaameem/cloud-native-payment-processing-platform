import { type Mode } from '@/lib/session/session';

/**
 * Every query key the portal uses, built in one place (M23.3).
 *
 * ── Mode is in the key, and that is a safety property rather than a cache detail ──────
 *
 * §6.6 states it outright: "Mode is part of every query key so a switch can never serve cached
 * cross-mode data." M23's own risk table names the failure it prevents — *mode confusion causes a
 * destructive action against live data* — and the shape of that failure is specific: a merchant
 * switches from test to live, a list re-renders from cache before the refetch lands, and for a
 * few hundred milliseconds they are looking at sandbox rows under a live badge. Every decision
 * they make in that window is made against the wrong data plane.
 *
 * Building keys through this module is what makes that impossible to get wrong. A caller cannot
 * write a key by hand without the scope, because the only exported way to produce one takes the
 * scope as its first argument.
 *
 * ── The merchant is in the key too ────────────────────────────────────────────────────
 *
 * A session cannot change merchant today — one owner, one merchant — so this looks redundant. It
 * is here because the cost is a string and the failure it forecloses is the worst one this
 * application could have: M24 adds an admin console that reads *other* merchants' objects for
 * support purposes, and on that day a cache keyed only by mode would serve one merchant's
 * payments under another's name. Adding the field later means auditing every key that already
 * exists; adding it now means the audit never happens.
 *
 * ── Why an array of segments and not a template string ────────────────────────────────
 *
 * TanStack Query matches keys structurally, so `['payments', scope]` invalidates every payments
 * query for that scope regardless of the filters appended after it. A string key would force
 * exact matches and turn "refresh the payments list after a refund" into a list of every filter
 * combination currently mounted.
 */

/** What a key must be scoped to before it is a key at all. */
export interface QueryScope {
  readonly merchantId: string;
  readonly mode: Mode;
}

/**
 * The scope segment, first in every key.
 *
 * Its own object rather than two loose strings so that a key read in a devtools panel says which
 * merchant and which mode it belongs to, rather than showing two anonymous strings whose meaning
 * depends on position.
 */
function scopeOf({ merchantId, mode }: QueryScope): { merchantId: string; mode: Mode } {
  return { merchantId, mode };
}

/**
 * The key factory.
 *
 * Every entry starts with the scope, so `queryClient.invalidateQueries({ queryKey: [scopeOf(s)] })`
 * clears exactly one merchant's one mode and nothing else.
 */
export const queryKeys = {
  /** Everything for one merchant in one mode. The root a mode switch invalidates. */
  scope: (scope: QueryScope) => [scopeOf(scope)] as const,

  /**
   * One platform operation with one set of arguments.
   *
   * Deliberately generic: M23.3 owns the data layer, not the screens, and a key factory that
   * enumerated `payments`, `refunds` and `events` would be M23.6's, M23.7's and M24's decisions
   * made early and in the wrong file. The operation id comes from the generated contract, so the
   * namespace is the platform's own and cannot drift from it.
   */
  operation: (scope: QueryScope, operationId: string, args?: Readonly<Record<string, unknown>>) =>
    [scopeOf(scope), operationId, args ?? {}] as const,

  /**
   * A single object fetched by id — the command palette's lookup, and every detail screen after
   * it.
   *
   * ── It is `operation` with one argument, and deliberately not its own key shape ──────
   *
   * The first draft gave this a distinct shape, on the reasoning that a detail view and a list
   * containing the same object should be invalidated independently. A test asserting that
   * property found it was already false *and already satisfied*: `getPayment` and `listPayments`
   * are different operation ids, so their keys differ at the second segment whatever this
   * function does. The extra discriminator would have separated `object(s, 'getPayment', id)`
   * from `operation(s, 'getPayment', { id })` — two spellings of one request — and quietly
   * doubled the cache entry for every detail screen that used the wrong one.
   *
   * So it delegates. The ergonomic difference is kept because `usePlatformObject` reads better
   * than passing `{ id }` by hand; the key is the same, which is what makes the two hooks share
   * a cache entry rather than compete for one.
   */
  object: (scope: QueryScope, operationId: string, id: string) =>
    queryKeys.operation(scope, operationId, { id }),
} as const;

export type QueryKeyFor = ReturnType<(typeof queryKeys)[keyof typeof queryKeys]>;
