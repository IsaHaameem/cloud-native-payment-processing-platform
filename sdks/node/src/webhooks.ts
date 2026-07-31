/**
 * Webhook signature verification (M22.4).
 *
 * ## Why this is the most important function in the package
 *
 * Everything else here is convenience: an integrator who skipped this SDK entirely would still
 * end up with working payments, just more slowly. This one is different — a receiver that does
 * not verify signatures will accept a forged `payment.captured` from anyone who learns the URL,
 * and a receiver that verifies the *body* but ignores the *timestamp* will accept a genuine
 * delivery replayed forever. §4.5 calls the timestamp the detail homegrown implementations most
 * often get wrong, and this function exists so that nobody has to get it right twice.
 *
 * ## The specification, which is M18's and not this SDK's
 *
 * ```
 * PaymentFlow-Signature: t=1785758400,v1=5f2c…9ab
 *
 *   signed_payload = "{t}" + "." + "{raw request body}"
 *   v1             = lowercase hex of HMAC-SHA256(secret, signed_payload)
 *   secret         = the endpoint's whsec_… value as UTF-8 bytes, prefix included
 * ```
 *
 * Several `v1` values may appear in one header, comma-separated: that is how the dual-secret
 * rotation window is expressed on the wire, so a receiver that has already switched and one
 * that has not both verify. Any match is a match.
 *
 * This implementation is checked against
 * `notification-service/src/test/resources/signature-vectors/webhook-signature-vectors.json`
 * — the same five vectors the platform's own signer, and the reference `verify.js` and
 * `verify.py`, are checked against. That file's header comment anticipates this SDK consuming
 * it, so a divergence fails a test here rather than being discovered by a merchant.
 */

import { createHmac, timingSafeEqual } from 'node:crypto';
import {
  WebhookPayloadError,
  WebhookSignatureError,
  WebhookTimestampError,
} from './errors.js';

/** The header every delivery carries. */
export const SIGNATURE_HEADER = 'PaymentFlow-Signature';

/**
 * The default tolerance window, in seconds.
 *
 * Five minutes, which is what the merchant-facing guide recommends. Wide enough to survive
 * ordinary clock drift between two servers, narrow enough that a captured delivery is not
 * replayable for the rest of the afternoon.
 */
export const DEFAULT_TOLERANCE_SECONDS = 300;

/**
 * The event envelope a verified delivery contains.
 *
 * Hand-written rather than reused from the generated models, because the two are genuinely
 * different shapes: `/v1/events` returns `EventResponse`, which has no `apiVersion`, while a
 * delivery does carry one. Returning the generated type would under-declare the object this
 * function actually hands back.
 */
export interface WebhookEvent {
  /** `evt_` followed by 32 hex characters. Stable across retries and replays — **dedupe on it**. */
  readonly id: string;
  /** Always `event`. */
  readonly object: string;
  /**
   * What happened: `payment.created`, `payment.authorized`, `payment.captured`,
   * `payment.failed`, `payment.voided`, `payment.refunded`, `payment.partially_refunded`.
   *
   * Typed as `string` and not a union on purpose. New event types are additive and ship
   * without a new API revision, so a handler must ignore what it does not recognise rather
   * than fail on it — a closed union would make the compiler enforce the opposite.
   */
  readonly type: string;
  /** The dated API revision the payload is shaped by. */
  readonly apiVersion?: string;
  /** When the event occurred, as RFC 3339. Not when it was delivered. */
  readonly created?: string;
  /** `test` or `live`. */
  readonly mode?: string;
  /**
   * The resource the event happened to, as it was at the time.
   *
   * `data.object` rather than `data` directly: the wrapper is the seam that lets a later
   * revision add siblings — `previousAttributes` being the obvious one — without changing the
   * type of `data`, which would be breaking. Its shape depends on `type` and is deliberately
   * open here, because a webhook handler branches on `type` before it reads the object.
   */
  readonly data: { readonly object?: Record<string, unknown> };
}

/** Options for {@link constructEvent}, for callers who prefer names to positions. */
export interface ConstructEventOptions {
  /** How far a delivery's timestamp may be from now, in seconds. Defaults to 300. */
  readonly toleranceSeconds?: number;
  /** The current time in epoch seconds. For tests; defaults to the system clock. */
  readonly nowEpochSeconds?: number;
}

/**
 * Verifies a delivery and returns its event.
 *
 * @param payload the **raw** request body, exactly as received. See the warning below.
 * @param signatureHeader the value of the `PaymentFlow-Signature` header.
 * @param secret the endpoint's `whsec_…` signing secret.
 * @param tolerance seconds of allowed clock skew, or an options object. Defaults to 300.
 *
 * @throws {WebhookSignatureError} the header is malformed, or nothing in it matched.
 * @throws {WebhookTimestampError} the signature is valid and its timestamp is out of window.
 * @throws {WebhookPayloadError} it verified and is not an event envelope.
 *
 * ## The raw body, and why it has to be raw
 *
 * The signature covers the bytes that were sent. `JSON.parse` followed by `JSON.stringify`
 * does not round-trip them — key order, whitespace and number formatting are all free to
 * change — so a caller who passes a re-serialized object will get a signature failure on a
 * delivery that was perfectly valid. In Express that means `express.raw({ type: 'application/json' })`
 * on this route, *before* `express.json()` sees it. This is the single most common way a
 * correct integration fails, which is why it is the first thing said here.
 *
 * A `Buffer` or `Uint8Array` is accepted directly and is the safer thing to pass, because it
 * cannot have been decoded and re-encoded on the way.
 */
export function constructEvent(
  payload: string | Uint8Array,
  signatureHeader: string,
  secret: string,
  tolerance: number | ConstructEventOptions = DEFAULT_TOLERANCE_SECONDS,
): WebhookEvent {
  const options: ConstructEventOptions = typeof tolerance === 'number' ? { toleranceSeconds: tolerance } : tolerance;
  const toleranceSeconds = options.toleranceSeconds ?? DEFAULT_TOLERANCE_SECONDS;
  const now = options.nowEpochSeconds ?? Math.floor(Date.now() / 1000);

  if (typeof secret !== 'string' || secret.length === 0) {
    throw new WebhookSignatureError('No signing secret. Pass the endpoint\'s `whsec_…` value.');
  }
  if (typeof signatureHeader !== 'string' || signatureHeader.length === 0) {
    throw new WebhookSignatureError(`No ${SIGNATURE_HEADER} header on the request.`);
  }
  if (!Number.isFinite(toleranceSeconds) || toleranceSeconds < 0) {
    throw new WebhookSignatureError('`tolerance` must be a non-negative number of seconds.');
  }

  const { timestamp, candidates } = parseHeader(signatureHeader);

  // Order matters, and this order is deliberate: the signature is checked *before* the
  // timestamp. Checking the window first would let anyone with the URL and a stopwatch learn
  // whether a body was correctly signed by observing which error came back — and it would
  // report a garbage header as "too old", which sends an integrator to look at their clock.
  const body = typeof payload === 'string' ? Buffer.from(payload, 'utf8') : Buffer.from(payload);
  const expected = sign(secret, timestamp, body);
  if (!candidates.some((candidate) => constantTimeEquals(candidate, expected))) {
    throw new WebhookSignatureError(
      'The signature does not match. Either the secret is wrong, or the payload is not the raw ' +
        'request body — re-serializing the JSON changes the bytes the signature covers.',
    );
  }

  const skew = Math.abs(now - timestamp);
  if (skew > toleranceSeconds) {
    // Absolute skew, not just "too old". A timestamp far in the future is equally a sign the
    // header was not produced for this delivery at this moment.
    throw new WebhookTimestampError(
      `The delivery's timestamp is ${skew}s away from now, outside the ${toleranceSeconds}s ` +
        'tolerance. This is a replayed delivery, or one of the two clocks is wrong.',
      timestamp,
      skew,
    );
  }

  return parseEvent(body.toString('utf8'));
}

/**
 * Computes the `v1` value for a body: lowercase hex HMAC-SHA256 over `"{timestamp}.{body}"`.
 *
 * Exported so a caller can build a signed request in their own tests without reimplementing
 * the specification from the guide — which is the moment they would get it subtly wrong, and
 * then write a test that passes against their own mistake.
 */
export function signPayload(secret: string, timestampEpochSeconds: number, payload: string | Uint8Array): string {
  const body = typeof payload === 'string' ? Buffer.from(payload, 'utf8') : Buffer.from(payload);
  return sign(secret, timestampEpochSeconds, body);
}

/** Builds a full header value, for the same reason {@link signPayload} is exported. */
export function signatureHeaderFor(
  secret: string,
  timestampEpochSeconds: number,
  payload: string | Uint8Array,
): string {
  return `t=${timestampEpochSeconds},v1=${signPayload(secret, timestampEpochSeconds, payload)}`;
}

// ── Internals ───────────────────────────────────────────────────────────────────────────

function sign(secret: string, timestamp: number, body: Buffer): string {
  const hmac = createHmac('sha256', Buffer.from(secret, 'utf8'));
  hmac.update(Buffer.from(`${timestamp}.`, 'utf8'));
  hmac.update(body);
  return hmac.digest('hex');
}

interface ParsedHeader {
  readonly timestamp: number;
  readonly candidates: readonly string[];
}

function parseHeader(header: string): ParsedHeader {
  let timestamp: number | undefined;
  const candidates: string[] = [];

  for (const element of header.split(',')) {
    const separator = element.indexOf('=');
    if (separator < 0) continue;
    const key = element.slice(0, separator).trim();
    const value = element.slice(separator + 1).trim();
    if (key === 't') {
      // Not `Number(value)`: that accepts '', '0x10' and '1e9', so a header with an empty
      // timestamp would parse as epoch zero and be reported as fifty years of clock skew.
      if (!/^\d+$/.test(value)) {
        throw new WebhookSignatureError(`The ${SIGNATURE_HEADER} header's timestamp is not an integer.`);
      }
      timestamp = Number(value);
    } else if (key === 'v1') {
      if (value.length > 0) candidates.push(value);
    }
    // Unknown fields are skipped rather than rejected. A future `v2` alongside `v1` is how
    // this scheme would gain a second algorithm, and a verifier that refused the whole header
    // on sight of an unfamiliar field would break on the day that shipped.
  }

  if (timestamp === undefined) {
    throw new WebhookSignatureError(`The ${SIGNATURE_HEADER} header has no \`t=\` timestamp.`);
  }
  if (candidates.length === 0) {
    throw new WebhookSignatureError(`The ${SIGNATURE_HEADER} header has no \`v1=\` signature.`);
  }
  return { timestamp, candidates };
}

/**
 * Compares two hex signatures without leaking where they first differ.
 *
 * `timingSafeEqual` throws on a length mismatch, so the length is checked first — which does
 * leak the length, and cannot leak anything else: a `v1` is always 64 hex characters, so a
 * candidate of any other length is malformed rather than a near miss.
 */
function constantTimeEquals(candidate: string, expected: string): boolean {
  const a = Buffer.from(candidate, 'utf8');
  const b = Buffer.from(expected, 'utf8');
  return a.length === b.length && timingSafeEqual(a, b);
}

function parseEvent(raw: string): WebhookEvent {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new WebhookPayloadError('The delivery verified but its body is not JSON.');
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new WebhookPayloadError('The delivery verified but its body is not a JSON object.');
  }

  const event = parsed as Partial<WebhookEvent>;
  // Exactly the three fields this function's return type promises, and no more. Validating
  // the rest would break §9's forward-compatibility promise the first time a field was added.
  if (typeof event.id !== 'string' || typeof event.type !== 'string' || typeof event.data !== 'object' || event.data === null) {
    throw new WebhookPayloadError(
      'The delivery verified but is not an event envelope — `id`, `type` and `data` are required.',
    );
  }
  return event as WebhookEvent;
}
