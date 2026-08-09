import 'server-only';

import { env } from '@/lib/env';

/**
 * The portal's calls to merchant-service's account plane (M23.2a).
 *
 * ── Why these are hand-written fetches and not `lib/api/transport` ────────────────────
 *
 * That module calls the **published `/v1` contract** through the generated operation
 * descriptors, which is exactly right for payments, refunds and everything else a merchant's own
 * integration can reach. Merchant onboarding is not on that tier and never will be: D182 fixes
 * `/api/v1` as the *account plane* — auth, users, merchant profile, keys — and D98 keeps it
 * undocumented and freely changeable, so it has no descriptors to call through. Routing it
 * through the generated transport would mean publishing it, which is the decision D182 declined.
 *
 * It is also chicken-and-egg: `/v1` is reached through M23.0's `SessionContextWebFilter`, which
 * resolves the caller's merchant in order to sign the internal context and answers **403** when
 * there is none. A user who has not onboarded therefore cannot use `/v1` at all — which is the
 * precise state onboarding exists to leave. The account plane takes the user's own JWT through
 * the gateway's OAuth2 resource server, so it answers before a merchant exists.
 *
 * ── Every outcome is a value; nothing here throws ─────────────────────────────────────
 *
 * The two callers are a login (which must not fail because a *secondary* fact could not be
 * established) and a form (which must render an error rather than a stack trace). Both want the
 * failure classified, not raised — so the return types carry it. See `identity.ts`, which makes
 * the opposite choice for the same reason: there, the caller genuinely has to distinguish four
 * outcomes across three call sites, and typed errors carry that further than a union would.
 */

/** The gateway is one hop away. */
const TIMEOUT_MS = 10_000;

/** `MerchantResponse`, as much of it as the portal renders. */
export interface MerchantProfile {
  readonly id: string;
  readonly businessName: string;
  readonly contactEmail: string;
  /** Absent until the merchant configures one; `null` on the wire is normalised away. */
  readonly webhookUrl: string | undefined;
}

/**
 * The three answers to "which merchant does this user own?".
 *
 * `absent` is a *state*, not a failure: `MerchantService.getMine` throws
 * `ResourceNotFoundException` for a user who has not onboarded, and 404 is the ordinary
 * condition of every freshly created account. `unavailable` is the one that must never be
 * confused with it — treating an outage as "no merchant" would march a fully onboarded merchant
 * back through onboarding and into a 409.
 */
export type MerchantLookup =
  | { readonly status: 'found'; readonly merchant: MerchantProfile }
  | { readonly status: 'absent' }
  | { readonly status: 'unavailable' };

export async function lookupMerchant(accessToken: string): Promise<MerchantLookup> {
  let response: Response;
  try {
    response = await fetch(`${env.gatewayUrl}/api/v1/merchants/me`, {
      headers: { Authorization: `Bearer ${accessToken}`, Accept: 'application/json' },
      cache: 'no-store',
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    return { status: 'unavailable' };
  }

  if (response.status === 404) return { status: 'absent' };
  if (!response.ok) return { status: 'unavailable' };

  const merchant = merchantFrom(await safeJson(response));
  // A 200 whose body we cannot describe is unavailability, never absence — see above.
  return merchant ? { status: 'found', merchant } : { status: 'unavailable' };
}

/** Why an onboarding attempt did not produce a merchant. */
export type OnboardFailure =
  /** 409 — this user already owns one. Recoverable: re-read it and carry on. */
  | 'already_exists'
  /** 400 — merchant-service refused the payload. */
  | 'invalid'
  /** 401/403 — the session is not entitled to do this. */
  | 'unauthorized'
  /** Unreachable, 5xx, or an answer we cannot describe. */
  | 'unavailable';

export type OnboardResult =
  | { readonly ok: true; readonly merchant: MerchantProfile }
  | { readonly ok: false; readonly reason: OnboardFailure };

/**
 * Creates the caller's merchant.
 *
 * `MerchantService.onboard` also issues the four starter API keys in the same transaction
 * (M15, §3.1 step 2) and returns their secrets — the only time they are ever readable, since
 * they are stored SHA-256 hashed. **This function deliberately does not return them**, and the
 * consequence is recorded rather than hidden: the secrets from onboarding are discarded, and a
 * merchant obtains a usable secret by rotating a key on the API-keys screen. The alternative —
 * revealing four secrets on a signup flow — is the highest-consequence UI in the product
 * (§5/M23 task 5 says so outright) and belongs with key management, not with account creation.
 */
export async function onboardMerchant(
  accessToken: string,
  input: { businessName: string; contactEmail: string },
): Promise<OnboardResult> {
  let response: Response;
  try {
    response = await fetch(`${env.gatewayUrl}/api/v1/merchants`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify(input),
      cache: 'no-store',
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    return { ok: false, reason: 'unavailable' };
  }

  if (response.status === 409) return { ok: false, reason: 'already_exists' };
  if (response.status === 400 || response.status === 422) return { ok: false, reason: 'invalid' };
  if (response.status === 401 || response.status === 403) {
    return { ok: false, reason: 'unauthorized' };
  }
  if (!response.ok) return { ok: false, reason: 'unavailable' };

  // `MerchantOnboardResponse` wraps the profile: `{ merchant, apiKeys }`. Only the first half is
  // read — see the note above about the second.
  const body = await safeJson(response);
  const merchant = merchantFrom((body as { merchant?: unknown } | undefined)?.merchant);
  if (!merchant) return { ok: false, reason: 'unavailable' };

  return { ok: true, merchant };
}

async function safeJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return undefined;
  }
}

/**
 * Validates the wire shape before it becomes a `MerchantProfile`.
 *
 * A 201 that does not carry an id is not a merchant, and letting one through would seal
 * `merchantId: undefined` into a session that has just been told onboarding succeeded — a user
 * bounced straight back to the page they just completed, with no error anywhere to explain it.
 */
function merchantFrom(value: unknown): MerchantProfile | undefined {
  if (typeof value !== 'object' || value === null) return undefined;
  const raw = value as Record<string, unknown>;

  if (typeof raw.id !== 'string' || raw.id.length === 0) return undefined;

  return {
    id: raw.id,
    businessName: typeof raw.businessName === 'string' ? raw.businessName : '',
    contactEmail: typeof raw.contactEmail === 'string' ? raw.contactEmail : '',
    // `null` is what Jackson writes for an unset column; the portal's types say `undefined`
    // throughout (D194), so the two are reconciled here rather than at every reader.
    webhookUrl: typeof raw.webhookUrl === 'string' ? raw.webhookUrl : undefined,
  };
}
