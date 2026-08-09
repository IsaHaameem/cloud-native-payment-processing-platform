'use client';

import {
  type UseInfiniteQueryResult,
  type UseQueryResult,
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import * as React from 'react';

import { queryKeys } from '@/lib/query/keys';
import { CURSOR_PARAMETER, type CursorPage, fetchPlatform } from '@/lib/query/platform';
import { useQueryScope } from '@/lib/query/scope';

/**
 * The hooks a screen uses to read the platform (M23.3).
 *
 * Three, because §6.6 names exactly three client-side jobs: read something, page through a list,
 * and invalidate after a mutation. Anything a screen needs beyond these belongs to that screen.
 *
 * All of them take the scope from context and build the key through `queryKeys`, so no caller
 * ever writes a key. That is the point: "mode is part of every query key" stops being a rule
 * people have to remember and becomes the only way to call these functions.
 */

type Params = Readonly<Record<string, string | number | boolean | undefined>>;

/**
 * One read of one operation.
 *
 * `enabled` is honoured so a detail view can wait for an id without a conditional hook — the
 * pattern React's rules of hooks otherwise push people into getting wrong.
 */
export function usePlatformQuery<T>(
  operationId: string,
  params: Params = {},
  options: { enabled?: boolean } = {},
): UseQueryResult<T, Error> {
  const scope = useQueryScope();

  return useQuery<T, Error>({
    queryKey: queryKeys.operation(scope, operationId, params),
    queryFn: ({ signal }) => fetchPlatform<T>(operationId, params, signal),
    enabled: options.enabled ?? true,
  });
}

/**
 * One object by id.
 *
 * Separate from {@link usePlatformQuery} only in the key it builds — `queryKeys.object` — so that
 * a detail view and a list containing the same object can be invalidated independently. A refund
 * should refresh the payment it belongs to without discarding every list the user has scrolled.
 */
export function usePlatformObject<T>(
  operationId: string,
  id: string | undefined,
  options: { enabled?: boolean } = {},
): UseQueryResult<T, Error> {
  const scope = useQueryScope();
  const enabled = (options.enabled ?? true) && id !== undefined && id.length > 0;

  return useQuery<T, Error>({
    queryKey: queryKeys.object(scope, operationId, id ?? ''),
    queryFn: ({ signal }) => fetchPlatform<T>(operationId, { id: id as string }, signal),
    enabled,
  });
}

/**
 * A cursor-paginated list (D107/D139).
 *
 * ── Why the cursor is never constructed here ──────────────────────────────────────────
 *
 * The contract is explicit that `nextCursor` is "opaque and signed: treat it as a token, never
 * parse or construct one". So the next page's parameter is the previous page's `nextCursor`
 * verbatim, and `getNextPageParam` returns `undefined` the moment `hasMore` is false — which is
 * what stops the list requesting a page past the end and being told so by an error.
 *
 * Offset pagination is deliberately not offered. `/v1` does not have it (D139), and a helper that
 * pretended otherwise would be a helper that produces 400s.
 */
export function usePlatformList<T>(
  operationId: string,
  params: Params = {},
  options: { enabled?: boolean } = {},
): UseInfiniteQueryResult<{ pages: CursorPage<T>[]; pageParams: unknown[] }, Error> {
  const scope = useQueryScope();

  return useInfiniteQuery<CursorPage<T>, Error, { pages: CursorPage<T>[]; pageParams: unknown[] }>({
    queryKey: queryKeys.operation(scope, operationId, params),
    queryFn: ({ pageParam, signal }) =>
      fetchPlatform<CursorPage<T>>(
        operationId,
        {
          ...params,
          ...(typeof pageParam === 'string' ? { [CURSOR_PARAMETER]: pageParam } : {}),
        },
        signal,
      ),
    initialPageParam: undefined,
    getNextPageParam: (lastPage) => (lastPage.hasMore === true ? lastPage.nextCursor : undefined),
    enabled: options.enabled ?? true,
  });
}

/**
 * Invalidation after a mutation.
 *
 * ── Scope-wide by default, and that is the right default here ─────────────────────────
 *
 * A capture changes the payment, the payments list, the balance, the events feed and the request
 * log. Enumerating those at each call site means every new surface has to be added to every
 * mutation that could affect it — and the one that gets forgotten shows the user a stale figure
 * about their own money. Invalidating the scope re-reads what is currently mounted and nothing
 * else, which on a dashboard is a handful of requests.
 *
 * `operation` is offered for the cases where the blast radius is genuinely known and small.
 */
export function useInvalidatePlatform(): {
  scope: () => Promise<void>;
  operation: (operationId: string) => Promise<void>;
} {
  const client = useQueryClient();
  const scope = useQueryScope();

  return React.useMemo(
    () => ({
      scope: async () => {
        await client.invalidateQueries({ queryKey: queryKeys.scope(scope) });
      },
      operation: async (operationId: string) => {
        await client.invalidateQueries({
          // A prefix match: `[scope, operationId]` invalidates that operation under every set of
          // parameters currently cached, which is what "refresh the payments list" has to mean
          // when four filter combinations are mounted.
          queryKey: [...queryKeys.scope(scope), operationId],
        });
      },
    }),
    [client, scope],
  );
}
