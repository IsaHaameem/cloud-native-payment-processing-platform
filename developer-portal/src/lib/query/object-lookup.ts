'use client';

import { useQuery } from '@tanstack/react-query';

import { queryKeys } from '@/lib/query/keys';
import { PlatformRequestError, fetchPlatform } from '@/lib/query/platform';
import { useOptionalQueryScope } from '@/lib/query/scope';

/**
 * Resolving a pasted object id against the platform (M23.3).
 *
 * `command-menu.tsx` has carried a note since M23.1 that this lands in M23.3 — "paste `pay_…` and
 * go". The note was written before the contract was, and it is wrong in a way worth recording:
 * **payments and refunds are identified by bare UUIDs**, not by prefixed ids. Only events carry a
 * prefix (`evt_` and 32 hex, per `EventResponse.id`). So there is no prefix to switch on for the
 * two objects a merchant is most likely to paste, and the lookup is built around what the
 * contract actually says.
 *
 * ── A UUID is ambiguous, and the platform is the only thing that can disambiguate ─────
 *
 * `/v1/payments/{id}` and `/v1/refunds/{id}` take the same shape of id and there is nothing in
 * the string to tell them apart. Rather than guess, the lookup asks: payment first, because that
 * is overwhelmingly what a merchant pastes, and refund only if the payment is a 404. Two requests
 * in the miss case, one in the common case, and no invented convention.
 *
 * ── Scoped like every other read ──────────────────────────────────────────────────────
 *
 * The key comes from `queryKeys.object`, so a lookup performed in test mode is not answered from
 * cache in live mode — the id may well exist in both planes and mean different money.
 */

export type ObjectKind = 'payment' | 'refund' | 'event';

/** What an id could be, before the platform is asked. */
export interface ObjectCandidate {
  readonly operationId: string;
  readonly kind: ObjectKind;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const EVENT_ID = /^evt_[0-9a-f]{32}$/i;

/**
 * @returns the operations worth trying for this input, in the order to try them. Empty when the
 *          input is not an id at all, which is the usual case — the palette is mostly used for
 *          navigation and must not fire a request per keystroke.
 */
export function candidatesFor(input: string): readonly ObjectCandidate[] {
  const id = input.trim();

  if (EVENT_ID.test(id)) return [{ operationId: 'getEvent', kind: 'event' }];
  if (UUID.test(id)) {
    return [
      { operationId: 'getPayment', kind: 'payment' },
      { operationId: 'getRefund', kind: 'refund' },
    ];
  }
  return [];
}

export interface ResolvedObject {
  readonly kind: ObjectKind;
  readonly id: string;
  /** The platform's own object, untyped here because three shapes share this path. */
  readonly data: Record<string, unknown>;
}

/**
 * Looks the input up, if it looks like an id at all.
 *
 * @returns `undefined` while idle or not-an-id; `null` when it is an id the merchant does not
 *          own or that does not exist. The two are different states on screen — nothing, versus
 *          "no object with that id" — and collapsing them would make every keystroke report a
 *          miss.
 */
export function useObjectLookup(input: string): {
  candidates: readonly ObjectCandidate[];
  result: ResolvedObject | null | undefined;
  isLoading: boolean;
  error: PlatformRequestError | undefined;
} {
  const scope = useOptionalQueryScope();
  const candidates = candidatesFor(input);
  const id = input.trim();
  const enabled = scope !== null && candidates.length > 0;

  const query = useQuery<ResolvedObject | null, Error>({
    // A fixed operation segment rather than one per candidate: the *input* is the identity of
    // this query, and which endpoint answers it is an implementation detail of the resolver.
    queryKey: scope
      ? queryKeys.object(scope, 'objectLookup', id)
      : ['objectLookup', id, 'unscoped'],
    queryFn: async ({ signal }) => resolve(id, candidates, signal),
    enabled,
    // An id either exists or does not; the answer does not go stale in the seconds a palette is
    // open, and re-asking on every reopen would spend two requests to redraw the same row.
    staleTime: 60_000,
    retry: false,
  });

  return {
    candidates,
    result: enabled ? (query.data ?? undefined) : undefined,
    isLoading: enabled && query.isPending,
    error: query.error instanceof PlatformRequestError ? query.error : undefined,
  };
}

async function resolve(
  id: string,
  candidates: readonly ObjectCandidate[],
  signal: AbortSignal,
): Promise<ResolvedObject | null> {
  for (const candidate of candidates) {
    try {
      const data = await fetchPlatform<Record<string, unknown>>(
        candidate.operationId,
        { id },
        signal,
      );
      return { kind: candidate.kind, id, data };
    } catch (error) {
      // A 404 means "not this kind" and the next candidate is tried. Anything else — a 401, a
      // 403, the platform being unwell — is the caller's answer and stops the search, because
      // continuing would turn a session problem into a misleading "no such object".
      if (error instanceof PlatformRequestError && error.status === 404) continue;
      throw error;
    }
  }
  return null;
}
