/**
 * Pagination, in both of the shapes this platform publishes (M22.2).
 *
 * §7.1's rule is that no SDK user should ever have to implement cursor handling, and the way
 * to make that true rather than aspirational is for the ordinary thing — a `for await` over a
 * list — to already be the paginating thing. A helper the caller has to know to reach for is a
 * helper most callers will not reach for, and their code will silently process only the first
 * page for as long as their account is small enough for that to look correct.
 *
 * ## Why there are two page types
 *
 * There are two on the wire. M19 put cursor pagination on every list it introduced, and D139
 * deliberately left `/v1/webhook_deliveries` and `/v1/test/decisions` on the older offset
 * `PageResponse`. Papering over that difference here would mean inventing a `totalElements` a
 * cursor page does not have — a cursor page reports no total precisely because counting costs
 * a second query — or hiding the total the offset endpoints genuinely do return. Both types
 * iterate identically, which is the part a caller actually cares about.
 */

import type { ResponseMeta } from './transport.js';

/** What both page types share, so a caller can write code against either. */
export interface Page<T> extends AsyncIterable<T> {
  /** Whether another page exists after this one. */
  readonly hasMore: boolean;
  /** What the exchange that produced *this* page reported. */
  readonly meta: ResponseMeta;
  /**
   * Fetches the next page, or resolves `undefined` when this is the last.
   *
   * Declared as `Page<T>` rather than `this` so that the two concrete page types can narrow
   * it to themselves. A polymorphic `this` return cannot be narrowed by an extending
   * interface — TypeScript has to assume some further subtype exists that the override would
   * not satisfy.
   */
  nextPage(): Promise<Page<T> | undefined>;
}

/**
 * A cursor page: the M19 list shape.
 *
 * No total count, deliberately — see the `hasMore` description in the published contract.
 */
export interface CursorPage<T> extends Page<T> {
  /** The objects on this page, most recent first. */
  readonly data: readonly T[];
  /** The cursor to pass as `starting_after` for the next page. */
  readonly nextCursor: string | undefined;
  nextPage(): Promise<CursorPage<T> | undefined>;
}

/** An offset page: the older `PageResponse` shape, on the two endpoints D139 left alone. */
export interface OffsetPage<T> extends Page<T> {
  /** The objects on this page. */
  readonly content: readonly T[];
  /** The zero-based index of this page. */
  readonly page: number;
  /** How many objects each page holds. */
  readonly size: number;
  /** How many objects match the query in total. */
  readonly totalElements: number;
  /** How many pages the result set spans. */
  readonly totalPages: number;
  nextPage(): Promise<OffsetPage<T> | undefined>;
}

/** The raw cursor envelope, as the contract publishes it. */
interface RawCursorPage<T> {
  readonly data?: T[] | undefined;
  readonly hasMore?: boolean | undefined;
  readonly nextCursor?: string | undefined;
}

/** The raw offset envelope, as the contract publishes it. */
interface RawOffsetPage<T> {
  readonly content?: T[] | undefined;
  readonly page?: number | undefined;
  readonly size?: number | undefined;
  readonly totalElements?: number | undefined;
  readonly totalPages?: number | undefined;
  readonly last?: boolean | undefined;
}

/** Fetches one page, given the pagination parameters for it. */
type CursorFetch<T> = (cursor: string | undefined) => Promise<{ body: RawCursorPage<T>; meta: ResponseMeta }>;
type OffsetFetch<T> = (page: number) => Promise<{ body: RawOffsetPage<T>; meta: ResponseMeta }>;

export function cursorPage<T>(body: RawCursorPage<T>, meta: ResponseMeta, fetch: CursorFetch<T>): CursorPage<T> {
  const data: readonly T[] = body.data ?? [];
  const nextCursor = body.nextCursor;
  // `hasMore` is the platform's own answer and is trusted where present. The fallback is not
  // `false`: a page carrying a cursor and no flag plainly has more, and stopping there would
  // silently truncate the result — the failure this whole file exists to prevent.
  const hasMore = body.hasMore ?? nextCursor !== undefined;

  const page: CursorPage<T> = {
    data,
    hasMore,
    nextCursor,
    meta,
    async nextPage(): Promise<CursorPage<T> | undefined> {
      if (!hasMore || nextCursor === undefined) return undefined;
      const next = await fetch(nextCursor);
      return cursorPage(next.body, next.meta, fetch);
    },
    [Symbol.asyncIterator](): AsyncIterator<T> {
      return iterate<T, CursorPage<T>>(page, (current) => current.data);
    },
  };
  return page;
}

export function offsetPage<T>(body: RawOffsetPage<T>, meta: ResponseMeta, fetch: OffsetFetch<T>): OffsetPage<T> {
  const content: readonly T[] = body.content ?? [];
  const index = body.page ?? 0;
  const totalPages = body.totalPages ?? 0;
  // `last` where the platform sent it; otherwise derived from the index, which the offset
  // envelope always carries enough of to compute.
  const hasMore = body.last === undefined ? index + 1 < totalPages : !body.last;

  const page: OffsetPage<T> = {
    content,
    page: index,
    size: body.size ?? content.length,
    totalElements: body.totalElements ?? content.length,
    totalPages,
    hasMore,
    meta,
    async nextPage(): Promise<OffsetPage<T> | undefined> {
      if (!hasMore) return undefined;
      const next = await fetch(index + 1);
      return offsetPage(next.body, next.meta, fetch);
    },
    [Symbol.asyncIterator](): AsyncIterator<T> {
      return iterate<T, OffsetPage<T>>(page, (current) => current.content);
    },
  };
  return page;
}

/**
 * Walks every object from this page onward, fetching as it goes.
 *
 * Written as a generator over `nextPage()` rather than as a loop that collects everything: a
 * list of every payment a merchant has ever taken is not a thing to hold in memory, and a
 * caller who `break`s out of the loop should stop making requests at that point rather than
 * after the last page.
 */
async function* iterate<T, P extends Page<T>>(first: P, items: (page: P) => readonly T[]): AsyncGenerator<T> {
  let current: P | undefined = first;
  while (current !== undefined) {
    for (const item of items(current)) {
      yield item;
    }
    current = (await current.nextPage()) as P | undefined;
  }
}
