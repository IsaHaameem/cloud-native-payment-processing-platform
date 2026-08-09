/**
 * The browser's half of the data layer (M23.3).
 *
 * Deliberately **not** `server-only`: this is the one module in `lib/` a client component is
 * meant to import. It holds no secret and cannot reach the platform — it calls the portal's own
 * `/api/platform/…` route, which supplies the credential. That separation is the whole design:
 * the browser names an operation, the server decides what that is allowed to mean.
 */

/** What a failed read tells the caller. Mirrors the route's error envelope. */
export class PlatformRequestError extends Error {
  readonly status: number;
  readonly code: string | undefined;
  readonly type: string | undefined;
  /** Quoted in a support request — §6.6 requires every surfaced error to carry it. */
  readonly requestId: string | undefined;

  constructor(
    message: string,
    detail: { status: number; code?: string; type?: string; requestId?: string },
  ) {
    super(message);
    this.name = 'PlatformRequestError';
    this.status = detail.status;
    this.code = detail.code;
    this.type = detail.type;
    this.requestId = detail.requestId;
  }

  /** True when the session is gone and the only useful response is to sign in again. */
  get isAuthentication(): boolean {
    return this.status === 401;
  }
}

interface ErrorEnvelope {
  error?: {
    type?: string;
    code?: string;
    message?: string;
    requestId?: string;
  };
}

/**
 * Builds the URL for a read.
 *
 * Path and query parameters go into one flat search string, because the route sorts them using
 * the generated descriptor and therefore does not need to be told which is which. `undefined`
 * values are dropped rather than serialised as the string "undefined" — the mistake that turns an
 * unset filter into a filter for the literal word.
 */
export function platformUrl(
  operationId: string,
  params: Readonly<Record<string, string | number | boolean | undefined>> = {},
): string {
  const search = new URLSearchParams();
  for (const [name, value] of Object.entries(params)) {
    if (value === undefined) continue;
    search.set(name, String(value));
  }
  const query = search.toString();
  return `/api/platform/${encodeURIComponent(operationId)}${query ? `?${query}` : ''}`;
}

/**
 * Performs one read.
 *
 * @throws {PlatformRequestError} for any non-2xx answer, carrying the platform's own code and
 *         request id where it supplied them.
 */
export async function fetchPlatform<T>(
  operationId: string,
  params: Readonly<Record<string, string | number | boolean | undefined>> = {},
  signal?: AbortSignal,
): Promise<T> {
  const response = await fetch(platformUrl(operationId, params), {
    headers: { Accept: 'application/json' },
    // The route already refuses to cache; this stops the *browser* from answering a mode switch
    // from its own HTTP cache, which no query key can protect against.
    cache: 'no-store',
    ...(signal ? { signal } : {}),
  });

  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as ErrorEnvelope;
    throw new PlatformRequestError(
      body.error?.message ?? `The request failed (${response.status}).`,
      {
        status: response.status,
        ...(body.error?.code !== undefined ? { code: body.error.code } : {}),
        ...(body.error?.type !== undefined ? { type: body.error.type } : {}),
        ...(body.error?.requestId !== undefined ? { requestId: body.error.requestId } : {}),
      },
    );
  }

  return (await response.json()) as T;
}

/**
 * One page of a cursor-paginated list, as the contract shapes them (D107/D139).
 *
 * Named here rather than imported from the generated models because the generator emits one
 * concrete `CursorPage…` interface per item type — `CursorPagePaymentResponse`,
 * `CursorPageEventResponse` and so on — and a data layer that has to name each of them is a data
 * layer that changes every time a list is added. The three fields are identical across all of
 * them, which is what makes the generic form safe.
 */
export interface CursorPage<T> {
  data?: T[];
  hasMore?: boolean;
  nextCursor?: string;
}

/** The contract's own spelling of the cursor parameter, in one place. */
export const CURSOR_PARAMETER = 'starting_after';
