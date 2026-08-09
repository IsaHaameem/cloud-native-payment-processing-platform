import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { lookupMerchant, onboardMerchant } from '@/lib/platform/merchants';
import { resolveMerchantId } from '@/lib/session/merchant';
import { SESSION_VERSION, type Session } from '@/lib/session/session';

/**
 * Merchant onboarding (M23.2a) — the step that turns a signed-in user into one who can act.
 *
 * The two properties worth proving here are the ones that produce a redirect loop when they are
 * wrong: that a successful onboarding **reseals the session** with the new merchant id, and that
 * a 409 is treated as a state to recover from rather than an error to report.
 */

const MERCHANT_ID = '66666666-7777-8888-9999-000000000000';

const fetchMock = vi.fn();

class RedirectSignal extends Error {
  constructor(readonly location: string) {
    super(`redirect:${location}`);
  }
}

vi.mock('next/navigation', () => ({
  redirect: (location: string) => {
    throw new RedirectSignal(location);
  },
}));

const assertSameOrigin = vi.hoisted(() => vi.fn());
const assertCsrf = vi.hoisted(() => vi.fn());
const persistSessionMock = vi.hoisted(() => vi.fn());
const readSessionMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/session/lifecycle', () => ({
  assertRequestIsSameOrigin: assertSameOrigin,
  persistSession: persistSessionMock,
}));
vi.mock('@/lib/session/require', () => ({ readSession: readSessionMock }));
vi.mock('@/lib/security/csrf', async () => {
  const actual = await vi.importActual<typeof import('@/lib/security/csrf')>('@/lib/security/csrf');
  return { ...actual, assertCsrfToken: assertCsrf };
});

const { onboardingAction } = await import('@/app/(setup)/onboarding/actions');

function jsonResponse(status: number, body?: unknown): Response {
  return new Response(body === undefined ? '' : JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function aSession(overrides: Partial<Session> = {}): Session {
  const now = Date.now();
  return {
    version: SESSION_VERSION,
    userId: '11111111-2222-3333-4444-555555555555',
    email: 'ada@example.com',
    roles: ['USER'],
    accessToken: 'the-access-token',
    accessExpiresAt: now + 900_000,
    refreshToken: 'the-refresh-token',
    refreshExpiresAt: now + 7 * 24 * 60 * 60 * 1000,
    merchantId: undefined,
    mode: 'test',
    createdAt: now,
    ...overrides,
  };
}

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  data.set('csrfToken', 'a'.repeat(64));
  for (const [key, value] of Object.entries(fields)) data.set(key, value);
  return data;
}

const VALID = { businessName: 'Ada Lovelace Ltd', contactEmail: 'billing@example.com' };

async function submit(fields: Record<string, string>) {
  try {
    const state = await onboardingAction({ error: undefined, field: undefined }, form(fields));
    return { state, location: undefined };
  } catch (error) {
    if (error instanceof RedirectSignal) return { state: undefined, location: error.location };
    throw error;
  }
}

const merchantBody = {
  id: MERCHANT_ID,
  businessName: VALID.businessName,
  contactEmail: VALID.contactEmail,
  webhookUrl: null,
};

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  assertSameOrigin.mockReset().mockResolvedValue(undefined);
  assertCsrf.mockReset().mockResolvedValue(undefined);
  persistSessionMock.mockReset().mockResolvedValue(true);
  readSessionMock.mockReset().mockResolvedValue(aSession());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('the merchant lookup', () => {
  it('reads a merchant the user owns', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, merchantBody));
    const lookup = await lookupMerchant('token');

    expect(lookup).toEqual({
      status: 'found',
      merchant: {
        id: MERCHANT_ID,
        businessName: VALID.businessName,
        contactEmail: VALID.contactEmail,
        // `null` on the wire becomes `undefined` here, so nothing downstream has to know that
        // Jackson and this codebase spell "not set" differently.
        webhookUrl: undefined,
      },
    });
  });

  it('reports 404 as absence, which is the ordinary state of a new account', async () => {
    fetchMock.mockResolvedValue(jsonResponse(404, { code: 'not_found' }));
    expect(await lookupMerchant('token')).toEqual({ status: 'absent' });
  });

  it('never reports an outage as absence', async () => {
    // The distinction that stops an onboarded merchant being marched back through onboarding
    // and into a 409 during a backend blip.
    fetchMock.mockResolvedValue(jsonResponse(503, {}));
    expect(await lookupMerchant('token')).toEqual({ status: 'unavailable' });

    fetchMock.mockRejectedValue(new TypeError('fetch failed'));
    expect(await lookupMerchant('token')).toEqual({ status: 'unavailable' });
  });

  it('treats a 200 with no id as unavailable rather than as a merchant', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { businessName: 'No id here' }));
    expect(await lookupMerchant('token')).toEqual({ status: 'unavailable' });
  });

  it('is what the login-time resolver reads, so there is one request shape', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, merchantBody));
    expect(await resolveMerchantId('token')).toBe(MERCHANT_ID);

    fetchMock.mockResolvedValue(jsonResponse(404, {}));
    expect(await resolveMerchantId('token')).toBeUndefined();
  });
});

describe('the onboarding call', () => {
  it('posts the contract shape and reads the merchant out of the wrapper', async () => {
    fetchMock.mockResolvedValue(jsonResponse(201, { merchant: merchantBody, apiKeys: [] }));
    const result = await onboardMerchant('token', VALID);

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/merchants');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual(VALID);
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer token');

    expect(result).toMatchObject({ ok: true, merchant: { id: MERCHANT_ID } });
  });

  it('classifies each refusal distinctly', async () => {
    for (const [status, reason] of [
      [409, 'already_exists'],
      [400, 'invalid'],
      [401, 'unauthorized'],
      [403, 'unauthorized'],
      [503, 'unavailable'],
    ] as const) {
      fetchMock.mockResolvedValue(jsonResponse(status, {}));
      expect(await onboardMerchant('token', VALID)).toEqual({ ok: false, reason });
    }
  });

  it('does not surface the issued API-key secrets', async () => {
    // They are returned exactly once by the platform and deliberately discarded here — the
    // once-only reveal is key management's screen, not account creation's.
    fetchMock.mockResolvedValue(
      jsonResponse(201, {
        merchant: merchantBody,
        apiKeys: [{ id: 'key_1', secret: 'sk_test_super_secret' }],
      }),
    );
    const result = await onboardMerchant('token', VALID);

    expect(JSON.stringify(result)).not.toContain('sk_test_super_secret');
  });
});

describe('the onboarding action', () => {
  it('creates the merchant, reseals the session, and continues to the dashboard', async () => {
    fetchMock.mockResolvedValue(jsonResponse(201, { merchant: merchantBody, apiKeys: [] }));
    const { location } = await submit(VALID);

    expect(location).toBe('/dashboard');
    // The property that makes the flow terminate: without this write, `/dashboard`'s guard reads
    // a session that still says "no merchant" and sends the user straight back here.
    expect(persistSessionMock).toHaveBeenCalledWith(
      expect.objectContaining({ merchantId: MERCHANT_ID }),
    );
  });

  it('recovers from a 409 instead of stranding the user on a form they cannot complete', async () => {
    fetchMock.mockImplementation((url: string, init?: RequestInit) =>
      Promise.resolve(
        init?.method === 'POST'
          ? jsonResponse(409, { code: 'conflict' })
          : jsonResponse(200, merchantBody),
      ),
    );

    const { location } = await submit(VALID);
    expect(location).toBe('/dashboard');
    expect(persistSessionMock).toHaveBeenCalledWith(
      expect.objectContaining({ merchantId: MERCHANT_ID }),
    );
  });

  it('does not seal a merchant id it could not confirm', async () => {
    // 409 says one exists; if the re-read then fails there is no id to trust, and inventing one
    // would put an unverified merchant into a signed cookie.
    fetchMock.mockImplementation((url: string, init?: RequestInit) =>
      Promise.resolve(init?.method === 'POST' ? jsonResponse(409, {}) : jsonResponse(503, {})),
    );

    const { state } = await submit(VALID);
    expect(state?.error).toMatch(/temporarily unavailable/i);
    expect(persistSessionMock).not.toHaveBeenCalled();
  });

  it('validates both fields before spending a request', async () => {
    const noName = await submit({ ...VALID, businessName: '   ' });
    expect(noName.state?.field).toBe('businessName');

    const longName = await submit({ ...VALID, businessName: 'x'.repeat(201) });
    expect(longName.state?.field).toBe('businessName');

    const badEmail = await submit({ ...VALID, contactEmail: 'nope' });
    expect(badEmail.state?.field).toBe('contactEmail');

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('sends an unauthenticated submission to sign in rather than to the platform', async () => {
    readSessionMock.mockResolvedValue(null);
    const { location } = await submit(VALID);

    expect(location).toBe('/login?next=%2Fonboarding');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuses a submission that fails CSRF, before touching the platform', async () => {
    const { CsrfError } = await import('@/lib/security/csrf');
    assertCsrf.mockRejectedValue(new CsrfError());

    const { state } = await submit(VALID);
    expect(state?.error).toMatch(/expired/i);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuses a cross-origin submission, before touching the platform', async () => {
    const { CrossOriginRequestError } = await import('@/lib/security/origin');
    assertSameOrigin.mockRejectedValue(new CrossOriginRequestError());

    const { state } = await submit(VALID);
    expect(state?.error).toMatch(/expired/i);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('never sends the session token anywhere but the Authorization header', async () => {
    fetchMock.mockResolvedValue(jsonResponse(201, { merchant: merchantBody, apiKeys: [] }));
    await submit(VALID);

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).not.toContain('the-access-token');
    expect(init.body as string).not.toContain('the-access-token');
  });
});
