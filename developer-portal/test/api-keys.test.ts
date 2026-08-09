import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { keyStatus, isLive, typeLabel } from '@/lib/api-keys/status';
import {
  type ApiKeySummary,
  createApiKey,
  listApiKeys,
  revokeApiKey,
  rotateApiKey,
} from '@/lib/platform/api-keys';
import { SESSION_VERSION, type Session } from '@/lib/session/session';

/**
 * API key management (M23.5).
 *
 * Three layers, and the boundaries between them are where the interesting failures live: the
 * status derivation (three nullable instants, one answer, and it has to agree with
 * `ApiKey.isActive`), the account-plane client (HTTP statuses into outcomes), and the Server
 * Actions (input and a session into a message, a secret, or neither).
 *
 * The property this file exists to defend is that **a secret exists for exactly one response**.
 * The reveal is the only place it is ever rendered, nothing reads it back, and no code path
 * outside `createApiKey`/`rotateApiKey` can produce one.
 */

const fetchMock = vi.fn();

const guard = vi.hoisted(() => vi.fn());
const readSessionMock = vi.hoisted(() => vi.fn());
const persistSessionMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/security/form-guard', async () => {
  const actual = await vi.importActual<typeof import('@/lib/security/form-guard')>(
    '@/lib/security/form-guard',
  );
  return { ...actual, guardFormRequest: guard };
});
vi.mock('@/lib/session/require', () => ({ readSession: readSessionMock }));
vi.mock('@/lib/session/lifecycle', () => ({ persistSession: persistSessionMock }));

const { createKeyAction, revokeKeyAction, rotateKeyAction } =
  await import('@/app/(app)/developers/api-keys/actions');

/**
 * The forms' initial state, restated rather than imported.
 *
 * The action module is `'use server'` and may export only async functions — exporting a constant
 * from one was a real M23.4 defect that compiled and failed at runtime. See `settings.test.ts`.
 */
const IDLE = {
  error: undefined,
  field: undefined,
  issued: undefined,
  done: undefined,
} as const;

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

function aSession(mode: 'test' | 'live' = 'test'): Session {
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
    mode,
    createdAt: now,
  };
}

function form(fields: Record<string, string | string[]>): FormData {
  const data = new FormData();
  data.set('csrfToken', 'a'.repeat(64));
  for (const [key, value] of Object.entries(fields)) {
    if (Array.isArray(value)) value.forEach((entry) => data.append(key, entry));
    else data.set(key, value);
  }
  return data;
}

function aKey(over: Partial<ApiKeySummary> = {}): ApiKeySummary {
  return {
    id: 'key-1',
    type: 'SECRET',
    mode: 'test',
    name: 'Production server',
    keyPrefix: 'sk_test_abc1',
    scopes: ['*'],
    lastUsedAt: undefined,
    expiresAt: undefined,
    graceExpiresAt: undefined,
    revokedAt: undefined,
    createdAt: '2026-08-01T09:00:00Z',
    ...over,
  };
}

/** The wire shape merchant-service actually sends: enum constants, `null` for unset instants. */
function wireKey(over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'key-1',
    type: 'SECRET',
    mode: 'TEST',
    name: 'Production server',
    keyPrefix: 'sk_test_abc1',
    scopes: ['*'],
    lastUsedAt: null,
    expiresAt: null,
    graceExpiresAt: null,
    revokedAt: null,
    createdAt: '2026-08-01T09:00:00Z',
    ...over,
  };
}

const lastRequest = () => fetchMock.mock.calls.at(-1) as [string, RequestInit];

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  guard.mockReset().mockResolvedValue(undefined);
  readSessionMock.mockReset().mockResolvedValue(aSession());
  persistSessionMock.mockReset().mockResolvedValue(true);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/* ── Status ────────────────────────────────────────────────────────────────────────── */

describe('key status', () => {
  const at = new Date('2026-08-09T12:00:00Z');

  it('is active when nothing has happened to it', () => {
    expect(keyStatus(aKey(), at)).toBe('active');
  });

  /**
   * The M23.5 field. Before it existed this case was indistinguishable from `active`, which is
   * the whole reason `ApiKeyResponse` gained a column.
   */
  it('is retiring while a rotation grace window is still open', () => {
    const key = aKey({ graceExpiresAt: '2026-08-10T12:00:00Z' });
    expect(keyStatus(key, at)).toBe('retiring');
    expect(isLive(keyStatus(key, at))).toBe(true);
  });

  it('is expired once that window has closed', () => {
    expect(keyStatus(aKey({ graceExpiresAt: '2026-08-09T11:59:59Z' }), at)).toBe('expired');
  });

  /**
   * Ordered exactly as `ApiKey.isActive` orders them. A key that is both revoked and expired must
   * read as revoked, because that is the thing a human did and the thing they may need to explain.
   */
  it('reports revocation ahead of expiry', () => {
    const key = aKey({ revokedAt: '2026-08-05T00:00:00Z', expiresAt: '2026-08-01T00:00:00Z' });
    expect(keyStatus(key, at)).toBe('revoked');
    expect(isLive(keyStatus(key, at))).toBe(false);
  });

  it('is expired at the instant of expiry, not after it', () => {
    expect(keyStatus(aKey({ expiresAt: '2026-08-09T12:00:00Z' }), at)).toBe('expired');
  });

  it('names the two types the way the documentation does', () => {
    expect(typeLabel('SECRET')).toBe('Secret');
    expect(typeLabel('PUBLISHABLE')).toBe('Publishable');
  });
});

/* ── The account-plane client ──────────────────────────────────────────────────────── */

describe('listing keys', () => {
  it('reads every key, both modes, revoked ones included', async () => {
    answer(200, [
      wireKey(),
      wireKey({ id: 'key-2', mode: 'LIVE', type: 'PUBLISHABLE' }),
      wireKey({ id: 'key-3', revokedAt: '2026-08-02T00:00:00Z' }),
    ]);

    const result = await listApiKeys('token');

    expect(result.status).toBe('found');
    if (result.status !== 'found') return;
    expect(result.keys).toHaveLength(3);
    expect(result.keys.map((key) => key.mode)).toEqual(['test', 'live', 'test']);
    expect(result.keys[2]?.revokedAt).toBe('2026-08-02T00:00:00Z');
  });

  it('asks the account plane, with the session token and no caching', async () => {
    answer(200, []);
    await listApiKeys('the-access-token');

    const [url, init] = lastRequest();
    expect(url).toContain('/api/v1/merchants/me/api-keys');
    expect(init.cache).toBe('no-store');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer the-access-token');
  });

  /** A single unreadable row must not cost a merchant the ability to revoke the others. */
  it('drops an entry it cannot describe rather than failing the page', async () => {
    answer(200, [
      wireKey(),
      { id: 'broken', type: 'MYSTERY', mode: 'TEST' },
      wireKey({ id: 'k3' }),
    ]);

    const result = await listApiKeys('token');

    expect(result.status).toBe('found');
    if (result.status !== 'found') return;
    expect(result.keys.map((key) => key.id)).toEqual(['key-1', 'k3']);
  });

  it('normalises null instants to undefined and enum modes to the portal vocabulary', async () => {
    answer(200, [wireKey({ mode: 'LIVE', lastUsedAt: null, graceExpiresAt: null })]);

    const result = await listApiKeys('token');
    if (result.status !== 'found') throw new Error('expected a listing');

    expect(result.keys[0]?.mode).toBe('live');
    expect(result.keys[0]?.lastUsedAt).toBeUndefined();
    expect(result.keys[0]?.graceExpiresAt).toBeUndefined();
  });

  it('treats a body that is not an array as unavailable', async () => {
    answer(200, { keys: [] });
    expect((await listApiKeys('token')).status).toBe('unavailable');
  });

  it('treats an unreachable gateway as unavailable rather than as an empty list', async () => {
    fetchMock.mockImplementation(() => Promise.reject(new Error('ECONNREFUSED')));
    expect((await listApiKeys('token')).status).toBe('unavailable');
  });
});

describe('creating a key', () => {
  it('sends the mode as the enum constant the API expects', async () => {
    answer(201, { id: 'k', apiKey: 'sk_live_secret', type: 'SECRET', mode: 'LIVE' });

    await createApiKey('token', { name: 'x', type: 'SECRET', mode: 'live', scopes: ['*'] });

    const body = JSON.parse(String(lastRequest()[1].body));
    expect(body.mode).toBe('LIVE');
    expect(body.type).toBe('SECRET');
  });

  it('returns the secret from the response and nothing else claims to have one', async () => {
    answer(201, {
      id: 'k',
      apiKey: 'sk_test_the_only_copy',
      keyPrefix: 'sk_test_the_',
      type: 'SECRET',
      mode: 'TEST',
      name: 'x',
      scopes: ['*'],
    });

    const result = await createApiKey('token', {
      name: 'x',
      type: 'SECRET',
      mode: 'test',
      scopes: ['*'],
    });

    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.key.apiKey).toBe('sk_test_the_only_copy');
  });

  /**
   * A 201 without a secret is a key that exists and can never be used. Reporting success would
   * render an empty reveal panel over a credential that is already unrecoverable.
   */
  it('refuses a creation response that carries no secret', async () => {
    answer(201, { id: 'k', type: 'SECRET', mode: 'TEST' });

    const result = await createApiKey('token', {
      name: 'x',
      type: 'SECRET',
      mode: 'test',
      scopes: ['*'],
    });

    expect(result).toEqual({ ok: false, reason: 'unavailable' });
  });

  it.each([
    [400, 'invalid'],
    [422, 'invalid'],
    [401, 'unauthorized'],
    [403, 'unauthorized'],
    [404, 'absent'],
    [500, 'unavailable'],
  ])('classifies %i as %s', async (status, reason) => {
    answer(status, {});
    const result = await createApiKey('token', {
      name: 'x',
      type: 'SECRET',
      mode: 'test',
      scopes: ['*'],
    });
    expect(result).toEqual({ ok: false, reason });
  });
});

describe('rotating and revoking', () => {
  it('rotates by id with no body at all', async () => {
    answer(200, { id: 'k2', apiKey: 'sk_test_new', type: 'SECRET', mode: 'TEST' });

    await rotateApiKey('token', 'key-1');

    const [url, init] = lastRequest();
    expect(url).toContain('/api/v1/merchants/me/api-keys/key-1/rotate');
    expect(init.method).toBe('POST');
    expect(init.body).toBeUndefined();
  });

  it('revokes with DELETE and accepts the 204 that carries no body', async () => {
    answer(204);

    const result = await revokeApiKey('token', 'key-1');

    expect(result).toEqual({ ok: true });
    expect(lastRequest()[1].method).toBe('DELETE');
  });

  /**
   * merchant-service resolves a key by `(id, merchantId)` together, so another merchant's key is
   * *not found*. The portal must carry that through unchanged: turning it into "forbidden" would
   * confirm that an id it cannot see exists.
   */
  it('reports another merchant’s key as absent, never as forbidden', async () => {
    answer(404, {});
    expect(await revokeApiKey('token', 'someone-elses')).toEqual({ ok: false, reason: 'absent' });
  });

  it('escapes an id rather than pasting it into the path', async () => {
    answer(204);
    await revokeApiKey('token', 'a/../b');
    expect(lastRequest()[0]).toContain('a%2F..%2Fb');
  });
});

/* ── The actions ───────────────────────────────────────────────────────────────────── */

describe('createKeyAction', () => {
  it('refuses before anything else when the CSRF guard rejects', async () => {
    guard.mockResolvedValue('csrf');

    const state = await createKeyAction(IDLE, form({ type: 'SECRET', mode: 'test', scopes: '*' }));

    expect(state.error).toBeDefined();
    expect(state.issued).toBeUndefined();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuses an origin the guard rejects, without calling the platform', async () => {
    guard.mockResolvedValue('origin');
    await createKeyAction(IDLE, form({ type: 'SECRET', mode: 'test', scopes: '*' }));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuses without a session', async () => {
    readSessionMock.mockResolvedValue(null);
    const state = await createKeyAction(IDLE, form({ type: 'SECRET', mode: 'test', scopes: '*' }));
    expect(state.error).toMatch(/sign in/i);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  /**
   * merchant-service does not validate scopes — `CreateApiKeyRequest.scopes` is a free
   * `List<String>` — so an unknown value would be stored, displayed, and grant nothing.
   */
  it('drops a scope that is not on the allow-list', async () => {
    answer(201, { id: 'k', apiKey: 'sk_test_x', type: 'SECRET', mode: 'TEST' });

    await createKeyAction(
      IDLE,
      form({ type: 'SECRET', mode: 'test', scopes: ['*', 'admin:everything'] }),
    );

    expect(JSON.parse(String(lastRequest()[1].body)).scopes).toEqual(['*']);
  });

  it('refuses when every submitted scope was rejected', async () => {
    const state = await createKeyAction(
      IDLE,
      form({ type: 'SECRET', mode: 'test', scopes: 'admin:everything' }),
    );

    expect(state.error).toMatch(/permission/i);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each([
    ['type', { type: 'ADMIN', mode: 'test', scopes: '*' }],
    ['mode', { type: 'SECRET', mode: 'sandbox', scopes: '*' }],
  ])('refuses an out-of-vocabulary %s', async (_what, fields) => {
    const state = await createKeyAction(IDLE, form(fields));
    expect(state.error).toBeDefined();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuses a name longer than the column', async () => {
    const state = await createKeyAction(
      IDLE,
      form({ name: 'n'.repeat(101), type: 'SECRET', mode: 'test', scopes: '*' }),
    );
    expect(state.field).toBe('name');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('allows a blank name, because the platform names the key itself', async () => {
    answer(201, { id: 'k', apiKey: 'sk_test_x', type: 'SECRET', mode: 'TEST' });
    const state = await createKeyAction(
      IDLE,
      form({ name: '  ', type: 'SECRET', mode: 'test', scopes: '*' }),
    );
    expect(state.error).toBeUndefined();
    expect(state.issued?.apiKey).toBe('sk_test_x');
  });

  /**
   * The regression for the defect that cost a secret.
   *
   * Resealing the session here made Next re-render this route inside the action's own response,
   * and under the new mode the page has a different shape — so React unmounted the subtree holding
   * the `useActionState` waiting for this very return value. Measured against the real stack: the
   * key was created, stored, and its only readable copy was destroyed before it reached the
   * screen. The mode change belongs after the reveal, on the acknowledge (see `actions.ts`).
   */
  it('does not touch the session when a key is made in the other mode', async () => {
    readSessionMock.mockResolvedValue(aSession('test'));
    answer(201, { id: 'k', apiKey: 'sk_live_x', type: 'SECRET', mode: 'LIVE' });

    const state = await createKeyAction(IDLE, form({ type: 'SECRET', mode: 'live', scopes: '*' }));

    expect(persistSessionMock).not.toHaveBeenCalled();
    expect(state.issued?.apiKey).toBe('sk_live_x');
  });

  /** The secret is what the caller came for; it must survive every branch of this action. */
  it('returns the secret whichever mode was chosen', async () => {
    readSessionMock.mockResolvedValue(aSession('test'));
    answer(201, { id: 'k', apiKey: 'sk_test_x', type: 'SECRET', mode: 'TEST' });

    const state = await createKeyAction(IDLE, form({ type: 'SECRET', mode: 'test', scopes: '*' }));

    expect(persistSessionMock).not.toHaveBeenCalled();
    expect(state.issued?.apiKey).toBe('sk_test_x');
  });

  it('reports the mode the platform confirmed, so the reveal can act on it', async () => {
    answer(201, { id: 'k', apiKey: 'sk_live_x', type: 'SECRET', mode: 'LIVE' });

    const state = await createKeyAction(IDLE, form({ type: 'SECRET', mode: 'live', scopes: '*' }));

    expect(state.issued?.mode).toBe('live');
  });

  it('never names a merchant in the request', async () => {
    answer(201, { id: 'k', apiKey: 'sk_test_x', type: 'SECRET', mode: 'TEST' });

    await createKeyAction(IDLE, form({ type: 'SECRET', mode: 'test', scopes: '*' }));

    const [url, init] = lastRequest();
    expect(url).toContain('/merchants/me/api-keys');
    expect(url).not.toContain('merchant-1');
    expect(String(init.body)).not.toContain('merchant-1');
  });
});

describe('rotateKeyAction', () => {
  it('is guarded like every other mutation', async () => {
    guard.mockResolvedValue('csrf');
    await rotateKeyAction(IDLE, form({ keyId: 'key-1' }));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('returns the replacement’s secret exactly once', async () => {
    answer(200, {
      id: 'key-2',
      apiKey: 'sk_test_the_replacement',
      keyPrefix: 'sk_test_the_',
      type: 'SECRET',
      mode: 'TEST',
      name: 'Production server',
      scopes: ['*'],
    });

    const state = await rotateKeyAction(IDLE, form({ keyId: 'key-1' }));

    expect(state.issued?.apiKey).toBe('sk_test_the_replacement');
    expect(state.error).toBeUndefined();
  });

  it('refuses a submission with no key id', async () => {
    const state = await rotateKeyAction(IDLE, form({ keyId: '' }));
    expect(state.error).toBeDefined();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('revokeKeyAction', () => {
  /**
   * The confirmation is checked against the **stored** name, not against a hidden field, so it
   * cannot be satisfied by a request that agrees with itself. This is the test that would fail if
   * the comparison were moved back into the form.
   */
  it('refuses when the typed name does not match what the platform holds', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(200, [wireKey()])));

    const state = await revokeKeyAction(IDLE, form({ keyId: 'key-1', confirmation: 'production' }));

    expect(state.field).toBe('confirmation');
    expect(fetchMock).toHaveBeenCalledTimes(1); // the listing only; no DELETE
  });

  it('cannot be satisfied by a request that supplies its own expected name', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(200, [wireKey()])));

    const state = await revokeKeyAction(
      IDLE,
      form({ keyId: 'key-1', keyName: 'anything', confirmation: 'anything' }),
    );

    expect(state.field).toBe('confirmation');
    expect(state.done).toBeUndefined();
  });

  it('revokes when the typed name matches the stored one', async () => {
    fetchMock
      .mockImplementationOnce(() => Promise.resolve(jsonResponse(200, [wireKey()])))
      .mockImplementationOnce(() => Promise.resolve(jsonResponse(204)));

    const state = await revokeKeyAction(
      IDLE,
      form({ keyId: 'key-1', confirmation: '  Production server  ' }),
    );

    expect(state.done).toBe('revoked');
    expect(lastRequest()[1].method).toBe('DELETE');
  });

  it('is case-sensitive, because two keys may differ only in case', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(200, [wireKey()])));

    const state = await revokeKeyAction(
      IDLE,
      form({ keyId: 'key-1', confirmation: 'production server' }),
    );

    expect(state.field).toBe('confirmation');
  });

  it('reports a key this merchant does not have as absent, without deleting anything', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(200, [wireKey()])));

    const state = await revokeKeyAction(
      IDLE,
      form({ keyId: 'someone-elses', confirmation: 'whatever' }),
    );

    expect(state.error).toMatch(/no longer exists/i);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('refuses when the listing itself is unavailable, rather than revoking blind', async () => {
    fetchMock.mockImplementation(() => Promise.reject(new Error('ECONNREFUSED')));

    const state = await revokeKeyAction(IDLE, form({ keyId: 'key-1', confirmation: 'x' }));

    expect(state.error).toMatch(/temporarily unavailable/i);
  });

  it('is guarded like every other mutation', async () => {
    guard.mockResolvedValue('csrf');
    await revokeKeyAction(IDLE, form({ keyId: 'key-1', confirmation: 'Production server' }));
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

/* ── The property the whole screen rests on ────────────────────────────────────────── */

describe('secret exposure', () => {
  /**
   * The management view is what `page.tsx` renders on every load. If a secret could ever appear on
   * it, the reveal would stop being once-only — so this asserts the shape rather than trusting it.
   */
  it('no listed key carries a secret, whatever the platform sends', async () => {
    answer(200, [wireKey({ apiKey: 'sk_test_leaked', secret: 'sk_test_also_leaked' })]);

    const result = await listApiKeys('token');
    if (result.status !== 'found') throw new Error('expected a listing');

    expect(JSON.stringify(result.keys)).not.toContain('sk_test_leaked');
    expect(JSON.stringify(result.keys)).not.toContain('sk_test_also_leaked');
  });

  /** A revocation reveals nothing; only creation and rotation ever produce a secret. */
  it('a revocation result has no secret in it', async () => {
    fetchMock
      .mockImplementationOnce(() => Promise.resolve(jsonResponse(200, [wireKey()])))
      .mockImplementationOnce(() => Promise.resolve(jsonResponse(204)));

    const state = await revokeKeyAction(
      IDLE,
      form({ keyId: 'key-1', confirmation: 'Production server' }),
    );

    expect(state.issued).toBeUndefined();
  });
});
