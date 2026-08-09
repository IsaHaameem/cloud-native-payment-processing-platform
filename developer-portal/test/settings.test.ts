import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { SettingsState } from '@/app/(app)/settings/actions';
import { updateMerchantProfile, updateMerchantWebhook } from '@/lib/platform/merchants';
import { fetchCurrentUser } from '@/lib/platform/users';
import { SESSION_VERSION, type Session } from '@/lib/session/session';

/**
 * Merchant settings (M23.4).
 *
 * Two layers, as elsewhere in this portal: the account-plane client, which turns HTTP statuses
 * into outcomes a screen can act on, and the Server Actions, which turn those plus the user's
 * input into a message or a saved state.
 *
 * The property worth being explicit about is that **the merchant is never named**. Both endpoints
 * are `/me`, so there is no id for the portal to get wrong — and the tests below assert that no
 * request this milestone makes carries one.
 */

const fetchMock = vi.fn();

const guard = vi.hoisted(() => vi.fn());
const readSessionMock = vi.hoisted(() => vi.fn());
const revalidateMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/security/form-guard', async () => {
  const actual = await vi.importActual<typeof import('@/lib/security/form-guard')>(
    '@/lib/security/form-guard',
  );
  return { ...actual, guardFormRequest: guard };
});
vi.mock('@/lib/session/require', () => ({ readSession: readSessionMock }));
vi.mock('next/cache', () => ({ revalidatePath: revalidateMock }));

/**
 * A successful save redirects rather than returning a state, so the destination is the assertion.
 * `next/navigation`'s `redirect` throws a sentinel in production; here it throws one this file
 * can read the target out of.
 */
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

/** Runs an action, reporting either the failure state or where it redirected. */
async function run(
  action: (previous: SettingsState, data: FormData) => Promise<SettingsState>,
  data: FormData,
) {
  try {
    return { state: await action(IDLE, data), location: undefined };
  } catch (error) {
    if (error instanceof RedirectSignal) return { state: undefined, location: error.location };
    throw error;
  }
}

const { updateBusinessAction, updateWebhookAction } = await import('@/app/(app)/settings/actions');

/**
 * The forms' initial state, restated here.
 *
 * It cannot be imported from the action module: that file is `'use server'` and may export only
 * async functions. Exporting it there was a real defect — the build accepted it and the page
 * failed at runtime the first time an action ran — so this duplication is the fix, not a
 * shortcut.
 */
const IDLE: SettingsState = { error: undefined, field: undefined, saved: undefined };

function jsonResponse(status: number, body?: unknown): Response {
  const bodiless = status === 204 || status === 304;
  return new Response(bodiless || body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function answer(status: number, body?: unknown): void {
  fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(status, body)));
}

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

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  data.set('csrfToken', 'a'.repeat(64));
  for (const [key, value] of Object.entries(fields)) data.set(key, value);
  return data;
}

const MERCHANT = {
  id: 'merchant-1',
  businessName: 'Ada Lovelace Ltd',
  contactEmail: 'billing@example.com',
  webhookUrl: null,
};

const VALID = { businessName: 'Ada Lovelace Ltd', contactEmail: 'billing@example.com' };

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  guard.mockReset().mockResolvedValue(undefined);
  readSessionMock.mockReset().mockResolvedValue(aSession());
  revalidateMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('reading the account', () => {
  it('reads the platform’s own user, not the session snapshot', async () => {
    answer(200, {
      id: 'user-1',
      email: 'ada@example.com',
      fullName: 'Ada Lovelace',
      roles: ['USER'],
      enabled: true,
      emailVerified: true,
      createdAt: '2026-08-01T10:00:00Z',
    });

    const lookup = await fetchCurrentUser('token');
    expect(lookup).toEqual({
      status: 'found',
      user: {
        id: 'user-1',
        email: 'ada@example.com',
        fullName: 'Ada Lovelace',
        roles: ['USER'],
        emailVerified: true,
        createdAt: '2026-08-01T10:00:00Z',
      },
    });

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('/api/v1/users/me');
  });

  it('normalises an unset name to undefined rather than null', async () => {
    answer(200, { id: 'user-1', email: 'ada@example.com', fullName: null, roles: [] });
    const lookup = await fetchCurrentUser('token');
    expect(lookup.status === 'found' && lookup.user.fullName).toBeUndefined();
  });

  it('treats an unverified account as unverified rather than absent', async () => {
    answer(200, { id: 'user-1', email: 'ada@example.com', emailVerified: false });
    expect(lookupUser(await fetchCurrentUser('token')).emailVerified).toBe(false);
  });

  it('reports a body it cannot describe as unavailable, not as a blank identity', async () => {
    // A settings page that renders an empty identity invites the user to trust it.
    answer(200, { email: 'ada@example.com' });
    expect(await fetchCurrentUser('token')).toEqual({ status: 'unavailable' });
  });

  it('never throws, whatever the platform does', async () => {
    answer(503, {});
    expect(await fetchCurrentUser('token')).toEqual({ status: 'unavailable' });

    fetchMock.mockRejectedValue(new TypeError('fetch failed'));
    expect(await fetchCurrentUser('token')).toEqual({ status: 'unavailable' });
  });
});

function lookupUser(lookup: Awaited<ReturnType<typeof fetchCurrentUser>>) {
  if (lookup.status !== 'found') throw new Error('expected a user');
  return lookup.user;
}

describe('the account-plane edits', () => {
  it('PATCHes /me and never names a merchant', async () => {
    // The isolation guarantee: the owner comes from the JWT subject at merchant-service, so there
    // is no id in the request for the portal to get wrong.
    answer(200, MERCHANT);
    await updateMerchantProfile('token', VALID);

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/merchants/me');
    expect(url).not.toContain('merchant-1');
    expect(init.method).toBe('PATCH');
    expect(JSON.parse(init.body as string)).toEqual(VALID);
    expect(init.body as string).not.toContain('merchant-1');
  });

  it('PATCHes the webhook on its own endpoint', async () => {
    answer(200, MERCHANT);
    await updateMerchantWebhook('token', 'https://example.com/hook');

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/merchants/me/webhook');
    expect(JSON.parse(init.body as string)).toEqual({ webhookUrl: 'https://example.com/hook' });
  });

  it('sends null to clear the webhook, which is what the contract documents', async () => {
    answer(200, MERCHANT);
    await updateMerchantWebhook('token', null);
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(init.body as string)).toEqual({ webhookUrl: null });
  });

  it('returns what the platform stored, not what was sent', async () => {
    // The two differ whenever a field is normalised — a blank webhook becoming null, say — and
    // the screen has to show the stored value or it is lying about the state of the account.
    answer(200, { ...MERCHANT, businessName: 'Normalised Ltd' });
    const result = await updateMerchantProfile('token', VALID);
    expect(result.ok && result.merchant.businessName).toBe('Normalised Ltd');
  });

  it('classifies each refusal distinctly', async () => {
    for (const [status, reason] of [
      [400, 'invalid'],
      [422, 'invalid'],
      [401, 'unauthorized'],
      [403, 'unauthorized'],
      [404, 'absent'],
      [503, 'unavailable'],
    ] as const) {
      answer(status, {});
      expect(await updateMerchantProfile('token', VALID)).toEqual({ ok: false, reason });
    }
  });

  it('treats an unreadable 200 as unavailable rather than as a save', async () => {
    answer(200, { businessName: 'no id here' });
    expect(await updateMerchantProfile('token', VALID)).toEqual({
      ok: false,
      reason: 'unavailable',
    });
  });
});

describe('the business profile action', () => {
  it('reports which section saved, so the form can navigate', async () => {
    answer(200, MERCHANT);
    const { state } = await run(updateBusinessAction, form(VALID));
    // The form turns this into `router.replace('/settings?saved=business')`, which re-renders the
    // page *and* the shared layout — the only arrangement of three that reliably updated the
    // header. See `actions.ts`.
    expect(state).toEqual({ error: undefined, field: undefined, saved: 'business' });
  });

  it('does not revalidate, because that would discard the confirmation', async () => {
    /*
     * The action used to call `revalidatePath('/', 'layout')` so the shell's header would pick up
     * the new business name. It did — and it re-rendered the tree holding the form, taking
     * `useActionState` and the "Saved" message with it. The browser suite caught it as a save
     * that persisted correctly and confirmed nothing, intermittently.
     *
     * The refresh moved to `router.refresh()` in the form, which re-fetches the same server data
     * and preserves client state. This guards the action against acquiring one again.
     */
    answer(200, MERCHANT);
    await run(updateBusinessAction, form(VALID));
    expect(revalidateMock).not.toHaveBeenCalled();
  });

  it('does not revalidate on the webhook path either', async () => {
    answer(200, MERCHANT);
    await run(updateWebhookAction, form({ webhookUrl: 'https://a.example/hook' }));
    expect(revalidateMock).not.toHaveBeenCalled();
  });

  it('sends both fields, because PATCH /me replaces both', async () => {
    answer(200, MERCHANT);
    await run(updateBusinessAction, form(VALID));
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(Object.keys(JSON.parse(init.body as string)).sort()).toEqual([
      'businessName',
      'contactEmail',
    ]);
  });

  it('normalises the contact address the way the platform does', async () => {
    answer(200, MERCHANT);
    await run(updateBusinessAction, form({ ...VALID, contactEmail: '  Billing@Example.COM ' }));
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(init.body as string).contactEmail).toBe('billing@example.com');
  });

  it('validates both fields before spending a request', async () => {
    expect(
      (await run(updateBusinessAction, form({ ...VALID, businessName: '  ' }))).state?.field,
    ).toBe('businessName');
    expect(
      (await run(updateBusinessAction, form({ ...VALID, businessName: 'x'.repeat(201) }))).state
        ?.field,
    ).toBe('businessName');
    expect(
      (await run(updateBusinessAction, form({ ...VALID, contactEmail: 'nope' }))).state?.field,
    ).toBe('contactEmail');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuses a submission that fails CSRF, before touching the platform', async () => {
    guard.mockResolvedValue('csrf');
    const { state } = await run(updateBusinessAction, form(VALID));
    expect(state?.error).toMatch(/expired/i);
    expect(state?.saved).toBeUndefined();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuses a cross-origin submission, and says which failure it was', async () => {
    guard.mockResolvedValue('origin');
    const { state } = await run(updateBusinessAction, form(VALID));
    expect(state?.error).toMatch(/did not come from the portal/i);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuses when there is no session', async () => {
    readSessionMock.mockResolvedValue(null);
    const { state } = await run(updateBusinessAction, form(VALID));
    expect(state?.error).toMatch(/no longer valid/i);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('does not claim a save when the platform refused', async () => {
    answer(503, {});
    const { state } = await run(updateBusinessAction, form(VALID));
    expect(state?.error).toMatch(/temporarily unavailable/i);
    expect(state?.saved).toBeUndefined();
  });
});

describe('the webhook action', () => {
  it('saves an https URL', async () => {
    answer(200, MERCHANT);
    const { state } = await run(
      updateWebhookAction,
      form({ webhookUrl: 'https://a.example/hook' }),
    );
    expect(state?.saved).toBe('callback');
  });

  it('treats an empty field as a deliberate clear, not a validation failure', async () => {
    answer(200, MERCHANT);
    const { state } = await run(updateWebhookAction, form({ webhookUrl: '   ' }));
    expect(state?.saved).toBe('callback');

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(init.body as string)).toEqual({ webhookUrl: null });
  });

  it('refuses a non-https URL before spending a request', async () => {
    // merchant-service enforces this too (`@Pattern(regexp = "^https://.+")`); rejecting it here
    // means the user is told why rather than shown a bean-validation message.
    const { state } = await run(updateWebhookAction, form({ webhookUrl: 'http://a.example/hook' }));
    expect(state?.field).toBe('webhookUrl');
    expect(state?.error).toMatch(/https/i);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('enforces the platform’s length bound', async () => {
    const long = `https://a.example/${'x'.repeat(2100)}`;
    const { state } = await run(updateWebhookAction, form({ webhookUrl: long }));
    expect(state?.field).toBe('webhookUrl');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuses a submission that fails the form guard', async () => {
    guard.mockResolvedValue('csrf');
    const { state } = await run(updateWebhookAction, form({ webhookUrl: 'https://a.example' }));
    expect(state?.error).toBeDefined();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('never puts the access token in a request body', async () => {
    answer(200, MERCHANT);
    await run(updateWebhookAction, form({ webhookUrl: 'https://a.example/hook' }));
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.body as string).not.toContain('the-access-token');
    expect(url).not.toContain('the-access-token');
  });
});
