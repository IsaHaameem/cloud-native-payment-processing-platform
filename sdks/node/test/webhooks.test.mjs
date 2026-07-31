/*
 * Webhook signature verification (M22.4).
 *
 * The first block runs the **shared vector file** — the same
 * `notification-service/src/test/resources/signature-vectors/webhook-signature-vectors.json`
 * that the platform's own `WebhookSigner`, the reference `verify.js` and the reference
 * `verify.py` are checked against. That file's header comment anticipates exactly this: "M22's
 * Node SDK helper is expected to consume this same vector file as its own fixture, so a
 * divergence there fails loudly rather than being discovered by a merchant."
 *
 * Asserting against the vectors rather than against this SDK's own output is what makes these
 * tests worth anything. A suite that signed a body and then verified it would pass just as
 * happily if the algorithm were wrong in both directions.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const {
  constructEvent,
  signPayload,
  signatureHeaderFor,
  webhooks,
  PaymentFlow,
  PaymentFlowError,
  WebhookVerificationError,
  WebhookSignatureError,
  WebhookTimestampError,
  WebhookPayloadError,
  SIGNATURE_HEADER,
  DEFAULT_TOLERANCE_SECONDS,
} = await import('../dist/esm/index.js');

const here = dirname(fileURLToPath(import.meta.url));
const VECTORS = join(here, '../../../notification-service/src/test/resources/signature-vectors/webhook-signature-vectors.json');

const doc = JSON.parse(await readFile(VECTORS, 'utf8'));

const SECRET = 'whsec_TestVectorSecretDoNotUseInProduction';
const EVENT_BODY = JSON.stringify({
  id: 'evt_3f2504e04f8941d39a0c0305e82c3301',
  object: 'event',
  type: 'payment.captured',
  apiVersion: '2026-08-01',
  created: '2026-08-01T12:00:00Z',
  mode: 'test',
  data: { object: { id: 'pay_1', object: 'payment', amountMinor: 5000, currency: 'USD' } },
});
const NOW = 1785758400;

/** Verifies at a fixed instant, so no test here depends on the wall clock. */
function verify(body, header, secret = SECRET, options = {}) {
  return constructEvent(body, header, secret, { nowEpochSeconds: NOW, ...options });
}

function headerAt(timestamp, body = EVENT_BODY, secret = SECRET) {
  return signatureHeaderFor(secret, timestamp, body);
}

// ── The shared vectors ──────────────────────────────────────────────────────────────────

test('the vector file is present and non-empty', () => {
  // Asserted first, so nothing below can pass by iterating an empty list — the failure mode
  // of every fixture-driven suite.
  assert.ok(Array.isArray(doc.vectors) && doc.vectors.length >= 5, 'at least the five published vectors');
  assert.equal(doc.algorithm, 'HMAC-SHA256');
});

test('this SDK reproduces every published signature vector exactly', () => {
  for (const vector of doc.vectors) {
    assert.equal(
      signPayload(vector.secret, vector.timestamp, vector.body),
      vector.expectedV1,
      `vector ${vector.name}`,
    );
  }
});

test('the signed payload is "{timestamp}.{body}", as the vectors spell it out', () => {
  for (const vector of doc.vectors) {
    assert.equal(vector.signedPayload, `${vector.timestamp}.${vector.body}`, `vector ${vector.name}`);
  }
});

test('a UTF-8 body signs over its bytes, not its code points', () => {
  const vector = doc.vectors.find((v) => v.name === 'unicode_body');
  assert.ok(vector, 'the unicode vector exists');

  // The same body as a Buffer must produce the same signature as the string. A receiver
  // frequently has the raw bytes and never decodes them, and that path has to agree.
  assert.equal(signPayload(vector.secret, vector.timestamp, vector.body), vector.expectedV1);
  assert.equal(
    signPayload(vector.secret, vector.timestamp, Buffer.from(vector.body, 'utf8')),
    vector.expectedV1,
  );
});

test('an empty body still signs, over "{timestamp}."', () => {
  const vector = doc.vectors.find((v) => v.name === 'empty_body');
  assert.ok(vector, 'the empty-body vector exists');
  assert.equal(signPayload(vector.secret, vector.timestamp, ''), vector.expectedV1);
});

test('the whsec_ prefix is part of the key and is not stripped', () => {
  const vector = doc.vectors[0];

  // Stripping it is the obvious "tidy-up" a reimplementation makes, and it produces a
  // signature that is wrong for every delivery while looking entirely reasonable.
  const stripped = signPayload(vector.secret.replace(/^whsec_/, ''), vector.timestamp, vector.body);
  assert.notEqual(stripped, vector.expectedV1);
});

// ── constructEvent: the happy path ──────────────────────────────────────────────────────

test('a well-formed delivery returns its event', () => {
  const event = verify(EVENT_BODY, headerAt(NOW));

  assert.equal(event.id, 'evt_3f2504e04f8941d39a0c0305e82c3301');
  assert.equal(event.type, 'payment.captured');
  assert.equal(event.apiVersion, '2026-08-01');
  assert.equal(event.mode, 'test');
  assert.equal(event.data.object.id, 'pay_1');
});

test('the raw body may be a Buffer, which is the safer thing to pass', () => {
  const event = verify(Buffer.from(EVENT_BODY, 'utf8'), headerAt(NOW));
  assert.equal(event.type, 'payment.captured');
});

test('a rotated endpoint sends both signatures and either one verifies', () => {
  const current = 'whsec_TheNewSecretAfterRotation';
  const superseded = SECRET;

  // The dual-secret rotation window on the wire: t=…,v1=<new>,v1=<old>. A receiver that has
  // switched and one that has not must both succeed, or rotation is an outage.
  const header =
    `t=${NOW}` +
    `,v1=${signPayload(current, NOW, EVENT_BODY)}` +
    `,v1=${signPayload(superseded, NOW, EVENT_BODY)}`;

  assert.equal(verify(EVENT_BODY, header, current).type, 'payment.captured');
  assert.equal(verify(EVENT_BODY, header, superseded).type, 'payment.captured');
});

test('an unknown field in the header is ignored rather than fatal', () => {
  // A future `v2` alongside `v1` is how this scheme would gain a second algorithm. A verifier
  // that rejected the whole header on sight of one would break on the day that shipped.
  const header = `${headerAt(NOW)},v2=notyetinvented`;
  assert.equal(verify(EVENT_BODY, header).type, 'payment.captured');
});

test('verification is reachable without constructing a client, and identically from one', () => {
  // A receiver is often a different process that holds no API key. Requiring a client would
  // mean either handing it a secret key it does not need, or not verifying.
  const fromNamespace = webhooks.constructEvent(EVENT_BODY, headerAt(NOW), SECRET, { nowEpochSeconds: NOW });
  const client = new PaymentFlow({ apiKey: 'sk_test_webhooks', fetch: async () => new Response('{}') });
  const fromClient = client.webhooks.constructEvent(EVENT_BODY, headerAt(NOW), SECRET, { nowEpochSeconds: NOW });

  assert.deepEqual(fromNamespace, fromClient);
  assert.equal(client.webhooks.SIGNATURE_HEADER, SIGNATURE_HEADER);
});

// ── constructEvent: rejection ───────────────────────────────────────────────────────────

test('a tampered body is rejected', () => {
  const header = headerAt(NOW);

  // One appended space. The whole point of the scheme.
  assert.throws(() => verify(`${EVENT_BODY} `, header), WebhookSignatureError);
});

test('a signature under the wrong secret is rejected', () => {
  assert.throws(() => verify(EVENT_BODY, headerAt(NOW), 'whsec_NotTheRightSecretAtAll'), WebhookSignatureError);
});

test('a re-serialized body is rejected, and the message says why', () => {
  // The single most common way a correct integration fails: JSON.parse then JSON.stringify
  // does not round-trip bytes, so the signature covers something the caller no longer has.
  // Signed over the body as it was sent — pretty-printed, as a proxy or a test harness might
  // emit it — and verified against what `express.json()` would hand a handler.
  const sent = JSON.stringify(JSON.parse(EVENT_BODY), null, 2);
  const reserialized = JSON.stringify(JSON.parse(sent));
  assert.notEqual(sent, reserialized, 'the two byte sequences really do differ');

  const error = attempt(() => verify(reserialized, headerAt(NOW, sent)));

  assert.ok(error instanceof WebhookSignatureError);
  assert.match(error.message, /raw request body/);
});

test('a stale delivery is rejected as a timestamp problem, not a signature problem', () => {
  const oldTimestamp = NOW - 8000;
  const error = attempt(() => verify(EVENT_BODY, headerAt(oldTimestamp)));

  // §7.1 requires these to throw distinctly: a valid signature arriving late is a replay or a
  // skewed clock, and neither is "your secret is wrong". Sending an integrator to check the
  // wrong thing is the cost of collapsing them.
  assert.ok(error instanceof WebhookTimestampError);
  assert.equal(error instanceof WebhookSignatureError, false);
  assert.equal(error.timestamp, oldTimestamp);
  assert.equal(error.skewSeconds, 8000);
});

test('a timestamp far in the future is rejected too', () => {
  // Absolute skew, not just "too old". A future timestamp is equally a sign the header was not
  // produced for this delivery at this moment.
  const error = attempt(() => verify(EVENT_BODY, headerAt(NOW + 8000)));
  assert.ok(error instanceof WebhookTimestampError);
  assert.equal(error.skewSeconds, 8000);
});

test('the tolerance window is inclusive at its edge and rejects one second past it', () => {
  const tolerance = 300;
  assert.equal(verify(EVENT_BODY, headerAt(NOW - tolerance), SECRET, { toleranceSeconds: tolerance }).type, 'payment.captured');
  assert.throws(
    () => verify(EVENT_BODY, headerAt(NOW - tolerance - 1), SECRET, { toleranceSeconds: tolerance }),
    WebhookTimestampError,
  );
});

test('the default tolerance is five minutes, as the merchant guide recommends', () => {
  assert.equal(DEFAULT_TOLERANCE_SECONDS, 300);

  // Exercised through the default path rather than only asserted as a constant.
  assert.throws(() => verify(EVENT_BODY, headerAt(NOW - 301)), WebhookTimestampError);
  assert.equal(verify(EVENT_BODY, headerAt(NOW - 299)).type, 'payment.captured');
});

test('the signature is checked before the timestamp', () => {
  // Otherwise a garbage header reads as "too old", which sends an integrator to look at their
  // clock — and the ordering would leak whether a body was correctly signed to anyone with the
  // URL and a stopwatch.
  const error = attempt(() => verify(`${EVENT_BODY} `, headerAt(NOW - 8000)));
  assert.ok(error instanceof WebhookSignatureError);
});

test('a malformed header is rejected with a message naming what is wrong', () => {
  const cases = [
    ['', /No PaymentFlow-Signature header/],
    ['nonsense', /no `t=` timestamp/],
    ['v1=abc', /no `t=` timestamp/],
    [`t=${NOW}`, /no `v1=` signature/],
    ['t=,v1=abc', /timestamp is not an integer/],
    ['t=notanumber,v1=abc', /timestamp is not an integer/],
    ['t=0x10,v1=abc', /timestamp is not an integer/],
  ];

  for (const [header, expected] of cases) {
    const error = attempt(() => verify(EVENT_BODY, header));
    assert.ok(error instanceof WebhookSignatureError, `"${header}" is a signature error`);
    assert.match(error.message, expected, `"${header}"`);
  }
});

test('an empty timestamp is not silently read as epoch zero', () => {
  // `Number('')` is 0, so a naive parse turns a malformed header into "fifty-six years of
  // clock skew" — a confusing timestamp error for what is really a broken header.
  const error = attempt(() => verify(EVENT_BODY, 't=,v1=abc'));
  assert.equal(error instanceof WebhookTimestampError, false);
});

test('a missing secret is refused rather than used to sign with an empty key', () => {
  assert.throws(() => verify(EVENT_BODY, headerAt(NOW), ''), WebhookSignatureError);
});

test('a negative tolerance is refused', () => {
  assert.throws(() => verify(EVENT_BODY, headerAt(NOW), SECRET, { toleranceSeconds: -1 }), WebhookSignatureError);
});

test('a signature of the wrong length is rejected without throwing from the comparison', () => {
  // `timingSafeEqual` throws on a length mismatch. A truncated `v1` must come back as a
  // verification failure, not as a TypeError escaping from inside the SDK.
  const error = attempt(() => verify(EVENT_BODY, `t=${NOW},v1=deadbeef`));
  assert.ok(error instanceof WebhookSignatureError);
});

test('a verified body that is not an event envelope is a distinct error', () => {
  for (const body of ['not json at all', '[1,2,3]', '"a string"', '{"id":"evt_1"}', '{}']) {
    const error = attempt(() => verify(body, headerAt(NOW, body)));
    assert.ok(error instanceof WebhookPayloadError, `${body} is a payload error`);
    // Not a signature error: the delivery was authentic, so telling the caller their secret is
    // wrong would send them to fix something that is not broken.
    assert.equal(error instanceof WebhookSignatureError, false);
  }
});

test('an event carrying fields this SDK does not know rides through untouched', () => {
  const body = JSON.stringify({
    id: 'evt_1',
    object: 'event',
    type: 'payment.something_invented_later',
    data: { object: { id: 'pay_1' }, previousAttributes: { status: 'authorized' } },
    aFieldFromTheFuture: true,
  });

  // New event types and new envelope fields are additive and ship without a new API revision.
  // A helper that validated the type against a closed list would make the platform's safest
  // change break every receiver at once.
  const event = verify(body, headerAt(NOW, body));
  assert.equal(event.type, 'payment.something_invented_later');
  assert.equal(event.aFieldFromTheFuture, true);
  assert.deepEqual(event.data.previousAttributes, { status: 'authorized' });
});

// ── The error hierarchy ─────────────────────────────────────────────────────────────────

test('every webhook error narrows from one base, and from PaymentFlowError', () => {
  const errors = [
    new WebhookSignatureError('x'),
    new WebhookTimestampError('x', 1, 2),
    new WebhookPayloadError('x'),
  ];

  for (const error of errors) {
    assert.ok(error instanceof WebhookVerificationError, `${error.name} extends WebhookVerificationError`);
    assert.ok(error instanceof PaymentFlowError, `${error.name} extends PaymentFlowError`);
    assert.equal(error.name, error.constructor.name, 'the class name survives into `error.name`');
    // There was no request, so there is no status to report.
    assert.equal(error.statusCode, undefined);
  }
});

function attempt(fn) {
  try {
    fn();
  } catch (error) {
    return error;
  }
  return assert.fail('expected a throw');
}
