/**
 * What every resource namespace shares (M22.3).
 *
 * Resource classes are thin on purpose: a method names an operation, hands over its
 * parameters, and returns what the API returned. Everything that could differ between them —
 * how a path is filled in, which query parameters are legal, when an idempotency key is
 * generated, what gets retried — lives in the transport, so that adding an endpoint cannot
 * accidentally add a behaviour.
 */

import type { OperationDescriptor } from '../generated/operations.js';
import type { RequestOptions, ResponseMeta, Transport } from '../transport.js';
import { cursorPage, offsetPage, type CursorPage, type OffsetPage } from '../pagination.js';

/** Query parameters as the contract spells them. `undefined` entries are not sent. */
export type QueryParams = Readonly<Record<string, unknown>>;

/** Everything one call can vary. Every field optional, because most calls vary none of it. */
export interface CallSpec {
  readonly path?: Readonly<Record<string, string>> | undefined;
  readonly query?: QueryParams | undefined;
  readonly body?: unknown;
  readonly options?: RequestOptions | undefined;
}

/** The cursor envelope, as far as the pagination helpers need to know it. */
interface RawCursor<T> {
  data?: T[] | undefined;
  hasMore?: boolean | undefined;
  nextCursor?: string | undefined;
}

/** The offset envelope, likewise. */
interface RawOffset<T> {
  content?: T[] | undefined;
  page?: number | undefined;
  size?: number | undefined;
  totalElements?: number | undefined;
  totalPages?: number | undefined;
  last?: boolean | undefined;
}

export abstract class Resource {
  constructor(protected readonly transport: Transport) {}

  /** One call, returning exactly the body the API sent. */
  protected async send<T>(operation: OperationDescriptor, spec: CallSpec = {}): Promise<T> {
    const result = await this.transport.request<T>({
      operation,
      path: spec.path,
      query: spec.query,
      body: spec.body,
      options: spec.options,
    });
    return result.data;
  }

  /**
   * A cursor-paginated list.
   *
   * The closure captures the caller's filters so that every subsequent page is fetched with
   * the same ones. Re-issuing a page request with different filters than the cursor was
   * minted under is the classic way an auto-paginating client returns a result set that never
   * existed.
   */
  protected async listCursor<T>(
    operation: OperationDescriptor,
    query: QueryParams,
    options: RequestOptions | undefined,
  ): Promise<CursorPage<T>> {
    const fetchPage = async (cursor: string | undefined): Promise<{ body: RawCursor<T>; meta: ResponseMeta }> => {
      const page = cursor === undefined ? query : { ...query, starting_after: cursor };
      const result = await this.transport.request<RawCursor<T>>({ operation, query: page, options });
      return { body: result.data, meta: result.meta };
    };

    const first = await fetchPage(undefined);
    return cursorPage(first.body, first.meta, fetchPage);
  }

  /** An offset-paginated list — the two endpoints D139 left on the older envelope. */
  protected async listOffset<T>(
    operation: OperationDescriptor,
    query: QueryParams,
    options: RequestOptions | undefined,
    path?: Readonly<Record<string, string>> | undefined,
  ): Promise<OffsetPage<T>> {
    const fetchPage = async (index: number): Promise<{ body: RawOffset<T>; meta: ResponseMeta }> => {
      const result = await this.transport.request<RawOffset<T>>({
        operation,
        path,
        query: { ...query, page: index },
        options,
      });
      return { body: result.data, meta: result.meta };
    };

    const requested = query['page'];
    const first = await fetchPage(typeof requested === 'number' ? requested : 0);
    return offsetPage(first.body, first.meta, fetchPage);
  }
}
