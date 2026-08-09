import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthenticationError, RateLimitError, toPlatformError } from '@/lib/api/errors';
import { SESSION_VERSION, type Session } from '@/lib/session/session';

/**
 * `/api/platform/[operation]` — the one door a browser may read the platform through (M23.3).
 *
 * This is the milestone's security surface: it is the first endpoint in the portal that takes an
 * *operation name from the client*. Most of what follows is about what it refuses.
 */

const callAsMock = vi.hoisted(() => vi.fn());
const readSessionMock = vi.hoisted(() => vi.fn());
const isSameOriginMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/client', () => ({ callAs: callAsMock }));
vi.mock('@/lib/session/require', () => ({ readSession: readSessionMock }));
vi.mock('@/lib/security/origin', async () => {
  const actual =
    await vi.importActual<typeof import('@/lib/security/origin')>('@/lib/security/origin');
  return { ...actual, isSameOrigin: isSameOriginMock };
});

const { GET } = await import('@/app/api/platform/[operation]/route');

function aSession(): Session {
  const now = Date.now();
  return {
    version: SESSION_VERSION,
    userId: 'user-1',
    email: 'ada@example.com',
    roles: ['USER'],
    accessToken: 'the-access-token',
    accessExpiresAt: now + 900_000,
    refreshToken: 'the-refresh-token',
    refreshExpiresAt: now + 7 * 24 * 60 * 60 * 1000,
    merchantId: 'merchant-1',
    mode: 'test',
    createdAt: now,
  };
}

/** The route's error envelope, plus whatever a success returns. */
interface ResponseBody {
  error?: { type?: string; code?: string; message?: string; requestId?: string };
  [key: string]: unknown;
}

/** Drives the handler the way Next.js does. */
async function get(operation: string, search = '') {
  const request = new Request(`http://localhost:3000/api/platform/${operation}${search}`);
  const response = await GET(request as never, { params: Promise.resolve({ operation }) });
  const body = (await response.json()) as ResponseBody;
  return { status: response.status, body, response };
}

beforeEach(() => {
  callAsMock.mockReset().mockResolvedValue({ data: [], hasMore: false });
  readSessionMock.mockReset().mockResolvedValue(aSession());
  isSameOriginMock.mockReset().mockReturnValue(true);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('what it refuses', () => {
  it('refuses a mutation, even a real one from the contract', async () => {
    // The property the whole design rests on: capture, refund and void are not reachable from the
    // browser through this path, so D190's confirmations and M23.2's CSRF token cannot be skipped
    // by calling an endpoint instead.
    for (const mutation of ['capturePayment', 'refundPayment', 'voidPayment', 'createPayment']) {
      const { status } = await get(mutation);
      expect(status).toBe(404);
    }
    expect(callAsMock).not.toHaveBeenCalled();
  });

  it('refuses an unknown operation the same way it refuses a mutation', async () => {
    expect((await get('noSuchOperation')).status).toBe(404);
  });

  it('refuses a request that did not come from the portal', async () => {
    isSameOriginMock.mockReturnValue(false);
    const { status } = await get('listPayments');
    expect(status).toBe(403);
    expect(callAsMock).not.toHaveBeenCalled();
  });

  it('answers 401 rather than redirecting when there is no session', async () => {
    // A 307 to an HTML login page arrives at code expecting JSON. The client layer needs a status
    // it can branch on.
    readSessionMock.mockResolvedValue(null);
    const { status, body } = await get('listPayments');
    expect(status).toBe(401);
    expect(body.error?.code).toBe('unauthorized');
    expect(callAsMock).not.toHaveBeenCalled();
  });

  it('refuses a parameter the operation does not document', async () => {
    // Passed through, the platform would ignore it and answer with a correct-looking *unfiltered*
    // page — the failure worth turning into an error.
    const { status, body } = await get('listPayments', '?nonsense=1');
    expect(status).toBe(400);
    expect(body.error?.code).toBe('unknown_parameter');
    expect(callAsMock).not.toHaveBeenCalled();
  });

  it('refuses a missing path parameter instead of building a broken URL', async () => {
    const { status, body } = await get('getPayment');
    expect(status).toBe(400);
    expect(body.error?.code).toBe('missing_parameter');
  });

  it('will not let the client choose the mode', async () => {
    // `mode` is not a parameter of any operation, so it is rejected before a request is built.
    // This is what stops a browser reading live data from a test-mode session.
    const { status } = await get('listPayments', '?mode=live');
    expect(status).toBe(400);
    expect(callAsMock).not.toHaveBeenCalled();
  });

  it('will not let the client choose the merchant', async () => {
    const { status } = await get('listPayments', '?merchantId=someone-else');
    expect(status).toBe(400);
    expect(callAsMock).not.toHaveBeenCalled();
  });
});

describe('what it does', () => {
  it('calls the operation with the session and returns the platform’s answer', async () => {
    callAsMock.mockResolvedValue({ data: [{ id: 'p1' }], hasMore: true, nextCursor: 'c1' });

    const { status, body } = await get('listPayments', '?limit=10&status=captured');
    expect(status).toBe(200);
    expect(body).toEqual({ data: [{ id: 'p1' }], hasMore: true, nextCursor: 'c1' });

    const [operationId, options] = callAsMock.mock.calls[0] as [string, Record<string, never>];
    expect(operationId).toBe('listPayments');
    expect(options.query).toEqual({ limit: '10', status: 'captured' });
    // The credential is the session's, chosen here and never by the caller.
    expect(options.session).toMatchObject({ accessToken: 'the-access-token', mode: 'test' });
  });

  it('sorts path parameters out of the query string using the descriptor', async () => {
    await get('getPayment', '?id=pay-1');
    const [, options] = callAsMock.mock.calls[0] as [string, Record<string, never>];
    expect(options.path).toEqual({ id: 'pay-1' });
    expect(options.query).toEqual({});
  });

  it('passes the cursor through as an ordinary documented parameter', async () => {
    await get('listPayments', '?starting_after=opaque-cursor');
    const [, options] = callAsMock.mock.calls[0] as [string, Record<string, never>];
    expect(options.query).toEqual({ starting_after: 'opaque-cursor' });
  });

  it('never lets a response be cached', async () => {
    // Every answer is per-merchant and per-mode.
    const { response } = await get('listPayments');
    expect(response.headers.get('cache-control')).toContain('no-store');
  });
});

describe('how failures reach the client', () => {
  it('forwards the platform’s status, code and request id', async () => {
    // §6.6: an error surface has to start a support conversation with an identifier.
    callAsMock.mockRejectedValue(
      toPlatformError({
        status: 429,
        body: {
          type: 'rate_limit_error',
          code: 'rate_limited',
          message: 'Too many requests.',
          requestId: 'req_abc123',
        },
        operationId: 'listPayments',
      }),
    );

    const { status, body } = await get('listPayments');
    expect(status).toBe(429);
    expect(body.error).toMatchObject({
      type: 'rate_limit_error',
      code: 'rate_limited',
      message: 'Too many requests.',
      requestId: 'req_abc123',
    });
  });

  it('classifies a 429 as a rate limit before forwarding it', async () => {
    // Guards the mapping the forwarding relies on.
    const error = toPlatformError({
      status: 429,
      body: { type: 'rate_limit_error', code: 'rate_limited', message: 'x' },
      operationId: 'listPayments',
    });
    expect(error).toBeInstanceOf(RateLimitError);
  });

  it('turns an expired session into 401 rather than an error the user cannot act on', async () => {
    callAsMock.mockRejectedValue(
      new AuthenticationError('gone', {
        status: 401,
        body: undefined,
        operationId: 'listPayments',
      }),
    );
    const { status, body } = await get('listPayments');
    expect(status).toBe(401);
    expect(body.error?.code).toBe('unauthorized');
  });

  it('never puts the access token in a response', async () => {
    callAsMock.mockRejectedValue(
      toPlatformError({
        status: 500,
        body: { type: 'api_error', code: 'internal', message: 'boom' },
        operationId: 'listPayments',
      }),
    );
    // The helper has already read the body, so assert on what it parsed rather than re-reading
    // a consumed stream.
    const { body } = await get('listPayments');
    expect(JSON.stringify(body)).not.toContain('the-access-token');
  });
});
