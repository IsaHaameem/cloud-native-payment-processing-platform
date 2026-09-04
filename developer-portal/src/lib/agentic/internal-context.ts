import 'server-only';

import { createHmac } from 'node:crypto';

import { env } from '@/lib/env';

/**
 * The portal's side of the platform's internal-context trust boundary (D100/D185), for the one
 * hop the gateway does not sit on: portal → agentic-commerce-service.
 *
 * ── Why this reproduces a Java class ──────────────────────────────────────────────────
 *
 * `common-lib`'s `InternalContextSigner` is what every backend service signs and verifies a
 * gateway-asserted merchant context with. The agentic service is reached server-side by this
 * portal rather than through the gateway (AD-8 keeps it off the gateway's routing so it stays
 * detachable), so the portal has to assert that context itself — and it must be *byte-identical*
 * to what the Java signer produces, because the agentic service's `InternalContextFilter`
 * verifies it with the real thing.
 *
 * The canonical string, the field order, the HMAC (`HmacSHA256`, lowercase hex) and the header
 * names are all transcribed from `InternalContextSigner.canonical` / `InternalContextHeaders`.
 * A change on either side that is not mirrored here breaks every agentic call with a 401, which
 * is the safe direction: a mismatch fails shut.
 *
 * ── What is asserted, and from where ──────────────────────────────────────────────────
 *
 * `principal = session`, and the merchant, mode and user all come from the sealed session — the
 * same values the gateway would derive from the session JWT for a `/v1` call. Nothing here is
 * read from a request body, query string or client header. The signing secret never leaves the
 * server; the browser holds neither it nor the resulting signature.
 */

/** Header names — transcribed from `common-lib` `InternalContextHeaders`. */
const HEADERS = {
  merchantId: 'X-PF-Internal-Merchant-Id',
  mode: 'X-PF-Internal-Mode',
  scopes: 'X-PF-Internal-Scopes',
  principal: 'X-PF-Internal-Principal',
  userId: 'X-PF-Internal-User-Id',
  issuedAt: 'X-PF-Internal-Issued-At',
  signature: 'X-PF-Internal-Signature',
} as const;

/**
 * A developer-portal session receives every scope at the gateway (`ApiScopes.ALL`), spelled as
 * the wildcard the platform's own `MerchantContext.hasScope` recognises. The agentic service
 * does not gate on scope, but the value is part of the signature so it must be exactly this.
 */
const SESSION_SCOPES = '*';

const PRINCIPAL_SESSION = 'session';

export interface InternalContextInput {
  readonly merchantId: string;
  readonly mode: 'test' | 'live';
  readonly userId: string;
}

/**
 * The canonical string `InternalContextSigner.canonical` builds for a session principal.
 *
 * `merchantId | mode | keyId | scopes | contactEmail | webhookUrl | issuedAt | principal | userId`
 *
 * For a session there is no key id, and the portal asserts no contact email or webhook url, so
 * those three positions are empty — exactly as the Java `nullToEmpty` renders a null.
 */
function canonical(input: InternalContextInput, issuedAtEpochSecond: number): string {
  return [
    input.merchantId,
    input.mode,
    '', // keyId — absent for a session
    SESSION_SCOPES,
    '', // contactEmail — not asserted by the portal
    '', // webhookUrl — not asserted by the portal
    String(issuedAtEpochSecond),
    PRINCIPAL_SESSION,
    input.userId,
  ].join('|');
}

/**
 * @returns the `X-PF-Internal-*` headers a request to the agentic service must carry, signed
 *          for this instant. `issuedAt` is seconds since the epoch; the verifier rejects a
 *          context more than its configured skew (30s) old, so these headers are minted per
 *          call and never cached.
 */
export function signedInternalContextHeaders(input: InternalContextInput): Record<string, string> {
  const issuedAt = Math.floor(Date.now() / 1000);
  const signature = createHmac('sha256', env.internalContextSecret)
    .update(canonical(input, issuedAt), 'utf8')
    .digest('hex');

  return {
    [HEADERS.merchantId]: input.merchantId,
    [HEADERS.mode]: input.mode,
    [HEADERS.scopes]: SESSION_SCOPES,
    [HEADERS.principal]: PRINCIPAL_SESSION,
    [HEADERS.userId]: input.userId,
    [HEADERS.issuedAt]: String(issuedAt),
    [HEADERS.signature]: signature,
  };
}
