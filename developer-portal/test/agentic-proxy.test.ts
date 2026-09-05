import { createHmac } from 'node:crypto';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PlatformError } from '@/lib/api/errors';
import { SESSION_VERSION, type Session } from '@/lib/session/session';

/**
 * The portal's server-side agentic proxy (M-agentic) — the boundary that lets the browser reach
 * `agentic-commerce-service` without ever holding the signing secret or calling that service
 * directly.
 *
 * Two things are load-bearing and tested here:
 *  1. The signed internal context is byte-identical to what `common-lib`'s `InternalContextSigner`
 *     produces for a session principal. A drift here fails every agentic call shut (401).
 *  2. The read routes refuse a cross-origin or session-less request as JSON, never a redirect,
 *     and never choose the merchant from anything but the sealed session.
 */

const readSessionMock = vi.hoisted(() => vi.fn());
const isSameOriginMock = vi.hoisted(() => vi.fn());
const fetchMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/session/require', async () => {
  const actual =
    await vi.importActual<typeof import('@/lib/session/require')>('@/lib/session/require');
  return { ...actual, readSession: readSessionMock };
});
vi.mock('@/lib/security/origin', async () => {
  const actual =
    await vi.importActual<typeof import('@/lib/security/origin')>('@/lib/security/origin');
  return { ...actual, isSameOrigin: isSameOriginMock };
});

const SECRET = 'dev-only-insecure-shared-secret-change-me';

function aMerchantSession(): Session {
  const now = Date.now();
  return {
    version: SESSION_VERSION,
    userId: '21a17a69-9bba-4943-937d-1c4884e5a6a1',
    email: 'ada@example.com',
    roles: ['USER'],
    accessToken: 'the-access-token',
    accessExpiresAt: now + 900_000,
    refreshToken: 'the-refresh-token',
    refreshExpiresAt: now + 7 * 24 * 60 * 60 * 1000,
    merchantId: '889e759a-23ea-4681-820c-396633218d3e',
    mode: 'test',
    createdAt: now,
  };
}

beforeEach(() => {
  vi.stubEnv('INTERNAL_CONTEXT_SECRET', SECRET);
  vi.stubEnv('AGENTIC_SERVICE_URL', 'http://agentic.test:8095');
  readSessionMock.mockReset().mockResolvedValue(aMerchantSession());
  isSameOriginMock.mockReset().mockReturnValue(true);
  fetchMock.mockReset().mockResolvedValue(
    new Response(JSON.stringify([{ id: 'apr_1', state: 'PENDING' }]), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    }),
  );
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe('signedInternalContextHeaders', () => {
  it('produces the exact canonical string InternalContextSigner expects for a session', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-29T12:00:00Z'));
    const { signedInternalContextHeaders } = await import('@/lib/agentic/internal-context');

    const merchantId = '889e759a-23ea-4681-820c-396633218d3e';
    const userId = '21a17a69-9bba-4943-937d-1c4884e5a6a1';
    const headers = signedInternalContextHeaders({ merchantId, mode: 'test', userId });

    const issuedAt = String(Math.floor(Date.parse('2026-08-29T12:00:00Z') / 1000));
    // merchantId | mode | keyId | scopes | contactEmail | webhookUrl | issuedAt | principal | userId
    const canonical = `${merchantId}|test||*|||${issuedAt}|session|${userId}`;
    const expected = createHmac('sha256', SECRET).update(canonical, 'utf8').digest('hex');

    expect(headers['X-PF-Internal-Merchant-Id']).toBe(merchantId);
    expect(headers['X-PF-Internal-Mode']).toBe('test');
    expect(headers['X-PF-Internal-Principal']).toBe('session');
    expect(headers['X-PF-Internal-User-Id']).toBe(userId);
    expect(headers['X-PF-Internal-Scopes']).toBe('*');
    expect(headers['X-PF-Internal-Issued-At']).toBe(issuedAt);
    expect(headers['X-PF-Internal-Signature']).toBe(expected);
    // A session context must NOT carry a key id.
    expect(headers['X-PF-Internal-Key-Id']).toBeUndefined();

    vi.useRealTimers();
  });

  it('mints a fresh signature each call (issued-at moves)', async () => {
    const { signedInternalContextHeaders } = await import('@/lib/agentic/internal-context');
    const a = signedInternalContextHeaders({ merchantId: 'm', mode: 'test', userId: 'u' });
    await new Promise((r) => setTimeout(r, 1100));
    const b = signedInternalContextHeaders({ merchantId: 'm', mode: 'test', userId: 'u' });
    expect(a['X-PF-Internal-Issued-At']).not.toBe(b['X-PF-Internal-Issued-At']);
    expect(a['X-PF-Internal-Signature']).not.toBe(b['X-PF-Internal-Signature']);
  });
});

describe('callAgentic', () => {
  it('sends the signed context and the mode from the session, never a caller value', async () => {
    const { callAgentic } = await import('@/lib/agentic/client');
    await callAgentic(aMerchantSession() as never, {
      method: 'GET',
      path: '/api/agentic/approvals',
    });

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    // The agentic service port, not the gateway — the browser's request never leaves the portal
    // and the portal talks to :8095 directly (AD-8: agentic is not gateway-routed).
    expect(url).toMatch(/:8095\/api\/agentic\/approvals$/);
    const headers = new Headers(init.headers);
    expect(headers.get('X-PF-Internal-Merchant-Id')).toBe('889e759a-23ea-4681-820c-396633218d3e');
    expect(headers.get('X-PF-Internal-Mode')).toBe('test');
    expect(headers.get('X-PF-Internal-Signature')).toMatch(/^[0-9a-f]{64}$/);
  });

  it('maps a non-2xx agentic response onto a PlatformError with the code and request id', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          type: 'permission_error',
          code: 'POLICY_REFUSED',
          message: 'no',
          requestId: 'req_9',
        }),
        { status: 403, headers: { 'content-type': 'application/json' } },
      ),
    );
    const { callAgentic } = await import('@/lib/agentic/client');
    await expect(
      callAgentic(aMerchantSession() as never, { method: 'GET', path: '/api/agentic/approvals' }),
    ).rejects.toMatchObject({ status: 403, code: 'POLICY_REFUSED', requestId: 'req_9' });
  });
});

describe('the read route', () => {
  it('refuses a cross-origin request with 403 and never calls the agentic service', async () => {
    isSameOriginMock.mockReturnValue(false);
    const { GET } = await import('@/app/api/agentic/approvals/route');
    const res = await GET(new Request('http://localhost:3000/api/agentic/approvals') as never);
    expect(res.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('returns 401 JSON (not a redirect) when there is no session', async () => {
    readSessionMock.mockResolvedValue(null);
    const { GET } = await import('@/app/api/agentic/approvals/route');
    const res = await GET(new Request('http://localhost:3000/api/agentic/approvals') as never);
    expect(res.status).toBe(401);
    const body = (await res.json()) as { error: { code: string } };
    expect(body.error.code).toBe('unauthorized');
  });

  it('returns 403 when the session has no merchant', async () => {
    readSessionMock.mockResolvedValue({ ...aMerchantSession(), merchantId: undefined });
    const { GET } = await import('@/app/api/agentic/approvals/route');
    const res = await GET(new Request('http://localhost:3000/api/agentic/approvals') as never);
    expect(res.status).toBe(403);
  });

  it('forwards an agentic PlatformError with its own status and request id', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(
        JSON.stringify({ code: 'FORBIDDEN', message: 'test-mode only', requestId: 'req_x' }),
        {
          status: 403,
          headers: { 'content-type': 'application/json' },
        },
      ),
    );
    const { GET } = await import('@/app/api/agentic/approvals/route');
    const res = await GET(new Request('http://localhost:3000/api/agentic/approvals') as never);
    expect(res.status).toBe(403);
    const body = (await res.json()) as { error: { requestId: string } };
    expect(body.error.requestId).toBe('req_x');
    expect(PlatformError).toBeDefined();
  });

  it('returns the agentic payload on success', async () => {
    const { GET } = await import('@/app/api/agentic/approvals/route');
    const res = await GET(new Request('http://localhost:3000/api/agentic/approvals') as never);
    expect(res.status).toBe(200);
    const body = (await res.json()) as Array<{ id: string }>;
    expect(body).toEqual([{ id: 'apr_1', state: 'PENDING' }]);
  });
});
