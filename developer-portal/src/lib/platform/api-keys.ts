import 'server-only';

import { env } from '@/lib/env';
import type { Mode } from '@/lib/session/session';

/**
 * The portal's calls to merchant-service's API-key management (M23.5).
 *
 * ── Why this is a hand-written client and not M23.3's generated transport ─────────────
 *
 * `/api/v1/merchants/me/api-keys` is the **account plane**, and its absence from the generated
 * descriptors is asserted rather than incidental: `PublicApiDocumentContract` fails the build if
 * any documented path starts with `/api/v1`. So there is no descriptor to call through, and
 * `/api/platform/[operation]` — which resolves generated GETs and nothing else (D207) — could not
 * serve this surface even if it were asked to. `merchants.ts` made the same call for the same
 * reason; this file follows it deliberately rather than inventing a second convention.
 *
 * ── Nothing throws, and the secret is never held ──────────────────────────────────────
 *
 * Every outcome is a value, as in `merchants.ts`: the callers are a page that must render and a
 * form that must explain itself. The raw secret appears in exactly one place — the return of
 * `createApiKey` and `rotateApiKey` — is passed straight to the caller, and is stored nowhere in
 * this module. There is no cache here, by construction: a cached list is a cached credential
 * inventory, and `cache: 'no-store'` on every call says so at each site.
 */

/** The gateway is one hop away. */
const TIMEOUT_MS = 10_000;

export type KeyType = 'PUBLISHABLE' | 'SECRET';

/**
 * `ApiKeyResponse` — the management view, which never carries a secret.
 *
 * `keyPrefix` is the first twelve characters of the raw key (`sk_live_a1b2`), which is what
 * identifies a key to a human without being able to authenticate as one.
 */
export interface ApiKeySummary {
  readonly id: string;
  readonly type: KeyType;
  readonly mode: Mode;
  readonly name: string;
  readonly keyPrefix: string;
  readonly scopes: readonly string[];
  readonly lastUsedAt: string | undefined;
  readonly expiresAt: string | undefined;
  /** Set only by a rotation: the moment this key stops authenticating. Added in M23.5. */
  readonly graceExpiresAt: string | undefined;
  readonly revokedAt: string | undefined;
  readonly createdAt: string;
}

/**
 * `ApiKeyIssuedResponse` — the one shape that carries the raw secret, at the one moment it exists
 * in a readable form anywhere in the platform.
 */
export interface IssuedApiKey {
  readonly id: string;
  readonly apiKey: string;
  readonly keyPrefix: string;
  readonly type: KeyType;
  readonly mode: Mode;
  readonly name: string;
  readonly scopes: readonly string[];
}

export type ApiKeyListing =
  | { readonly status: 'found'; readonly keys: readonly ApiKeySummary[] }
  | { readonly status: 'unavailable' };

/** Why a key operation did not happen. */
export type KeyFailure =
  /** 400/422 — merchant-service refused the payload. */
  | 'invalid'
  /** 401/403 — the session is not entitled. */
  | 'unauthorized'
  /** 404 — no such key belongs to this merchant. Ownership and existence are one answer (see below). */
  | 'absent'
  /** Unreachable, 5xx, or an answer we cannot describe. */
  | 'unavailable';

export type IssueResult =
  | { readonly ok: true; readonly key: IssuedApiKey }
  | { readonly ok: false; readonly reason: KeyFailure };

export type RevokeResult =
  { readonly ok: true } | { readonly ok: false; readonly reason: KeyFailure };

/**
 * Every key this merchant has ever been issued, newest first — both modes, and revoked ones too.
 *
 * merchant-service applies no mode filter and never deletes a key, so the caller does the
 * narrowing. That is deliberate on both sides: a revoked key is the audit trail of a credential
 * that once existed, and hiding it at the API would make "was this key ever real?" unanswerable.
 */
export async function listApiKeys(accessToken: string): Promise<ApiKeyListing> {
  let response: Response;
  try {
    response = await fetch(`${env.gatewayUrl}/api/v1/merchants/me/api-keys`, {
      headers: { Authorization: `Bearer ${accessToken}`, Accept: 'application/json' },
      cache: 'no-store',
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    return { status: 'unavailable' };
  }

  if (!response.ok) return { status: 'unavailable' };

  const body = await safeJson(response);
  if (!Array.isArray(body)) return { status: 'unavailable' };

  // A malformed entry is dropped rather than failing the page: one unreadable row must not cost a
  // merchant the ability to revoke the other nine.
  const keys = body.map(summaryFrom).filter((key): key is ApiKeySummary => key !== undefined);
  return { status: 'found', keys };
}

/**
 * Issues a new key and returns its secret — the only time the platform will ever produce it.
 *
 * `scopes` is optional at the API; omitted, merchant-service applies the type-appropriate default
 * (`["payments:read"]` for publishable, `["*"]` for secret). The portal always sends an explicit
 * list, because a scope picker that quietly disagreed with the server's default would be worse
 * than no picker.
 */
export async function createApiKey(
  accessToken: string,
  input: { name: string; type: KeyType; mode: Mode; scopes: readonly string[] },
): Promise<IssueResult> {
  return issuing(accessToken, '/api/v1/merchants/me/api-keys', {
    name: input.name,
    type: input.type,
    mode: input.mode.toUpperCase(),
    scopes: input.scopes,
  });
}

/**
 * Replaces a key with a new one of the same type, mode, name and scopes, and starts the old one's
 * grace window.
 *
 * The window is merchant-service's to set (`rotation-grace-period`, 24h) and its end comes back on
 * the old key as `graceExpiresAt` — which is what lets the screen say *when* the old key dies
 * rather than only that it will. The portal does not restate the duration as a constant of its
 * own; a hardcoded "24 hours" would be a second source of truth that goes quietly wrong the day
 * the property changes.
 */
export async function rotateApiKey(accessToken: string, keyId: string): Promise<IssueResult> {
  return issuing(
    accessToken,
    `/api/v1/merchants/me/api-keys/${encodeURIComponent(keyId)}/rotate`,
    undefined,
  );
}

/**
 * Revokes a key immediately.
 *
 * Immediately is literal: `ApiKeyService.revoke` deletes the gateway's cached verification for the
 * key outright rather than letting the entry lapse, so the next request made with it fails. That
 * is the property the confirmation friction on this action is protecting.
 */
export async function revokeApiKey(accessToken: string, keyId: string): Promise<RevokeResult> {
  let response: Response;
  try {
    response = await fetch(
      `${env.gatewayUrl}/api/v1/merchants/me/api-keys/${encodeURIComponent(keyId)}`,
      {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${accessToken}`, Accept: 'application/json' },
        cache: 'no-store',
        signal: AbortSignal.timeout(TIMEOUT_MS),
      },
    );
  } catch {
    return { ok: false, reason: 'unavailable' };
  }

  const failure = failureFor(response);
  return failure ? { ok: false, reason: failure } : { ok: true };
}

async function issuing(accessToken: string, path: string, body: unknown): Promise<IssueResult> {
  let response: Response;
  try {
    response = await fetch(`${env.gatewayUrl}${path}`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        Accept: 'application/json',
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      cache: 'no-store',
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    return { ok: false, reason: 'unavailable' };
  }

  const failure = failureFor(response);
  if (failure) return { ok: false, reason: failure };

  const issued = issuedFrom(await safeJson(response));
  // A success we cannot read is not a success: the key exists now and its secret is already lost,
  // so saying "unavailable" is the only honest answer and the list will show what was created.
  return issued ? { ok: true, key: issued } : { ok: false, reason: 'unavailable' };
}

/**
 * @returns the classified failure, or `undefined` if the response succeeded.
 *
 * 404 becomes `absent` and means exactly one thing to the caller. merchant-service looks a key up
 * by `(id, merchantId)` together, so a key belonging to someone else is *not found* rather than
 * *forbidden* — a distinction the portal must not undo by guessing, since the difference between
 * those two answers is the difference between confirming and denying that an id exists.
 */
function failureFor(response: Response): KeyFailure | undefined {
  if (response.status === 400 || response.status === 422) return 'invalid';
  if (response.status === 401 || response.status === 403) return 'unauthorized';
  if (response.status === 404) return 'absent';
  if (!response.ok) return 'unavailable';
  return undefined;
}

async function safeJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return undefined;
  }
}

function summaryFrom(value: unknown): ApiKeySummary | undefined {
  if (typeof value !== 'object' || value === null) return undefined;
  const raw = value as Record<string, unknown>;

  const type = keyTypeFrom(raw.type);
  const mode = modeFrom(raw.mode);
  if (typeof raw.id !== 'string' || !type || !mode) return undefined;

  return {
    id: raw.id,
    type,
    mode,
    name: typeof raw.name === 'string' ? raw.name : '',
    keyPrefix: typeof raw.keyPrefix === 'string' ? raw.keyPrefix : '',
    scopes: stringsFrom(raw.scopes),
    lastUsedAt: instantFrom(raw.lastUsedAt),
    expiresAt: instantFrom(raw.expiresAt),
    graceExpiresAt: instantFrom(raw.graceExpiresAt),
    revokedAt: instantFrom(raw.revokedAt),
    createdAt: instantFrom(raw.createdAt) ?? '',
  };
}

function issuedFrom(value: unknown): IssuedApiKey | undefined {
  if (typeof value !== 'object' || value === null) return undefined;
  const raw = value as Record<string, unknown>;

  const type = keyTypeFrom(raw.type);
  const mode = modeFrom(raw.mode);
  // The secret is the entire point of this shape. A response without one is not a key the caller
  // can do anything with, and pretending otherwise would show an empty reveal panel for a
  // credential that does exist — the worst of both outcomes.
  if (typeof raw.id !== 'string' || typeof raw.apiKey !== 'string' || !type || !mode) {
    return undefined;
  }

  return {
    id: raw.id,
    apiKey: raw.apiKey,
    keyPrefix: typeof raw.keyPrefix === 'string' ? raw.keyPrefix : raw.apiKey.slice(0, 12),
    type,
    mode,
    name: typeof raw.name === 'string' ? raw.name : '',
    scopes: stringsFrom(raw.scopes),
  };
}

/** Jackson writes the enum constants; the portal's vocabulary is lower case for mode (D194). */
function modeFrom(value: unknown): Mode | undefined {
  if (value === 'TEST' || value === 'test') return 'test';
  if (value === 'LIVE' || value === 'live') return 'live';
  return undefined;
}

function keyTypeFrom(value: unknown): KeyType | undefined {
  return value === 'PUBLISHABLE' || value === 'SECRET' ? value : undefined;
}

function stringsFrom(value: unknown): readonly string[] {
  return Array.isArray(value)
    ? value.filter((entry): entry is string => typeof entry === 'string')
    : [];
}

/** `null` is what Jackson writes for an unset timestamp; the portal's types say `undefined` (D194). */
function instantFrom(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}
