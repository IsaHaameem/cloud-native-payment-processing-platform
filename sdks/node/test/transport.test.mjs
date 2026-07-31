/*
 * The request pipeline: what goes on the wire, and what happens when it comes back wrong.
 *
 * Every test here drives a real client through a stub `fetch`, which is the reason §7.1 makes
 * the HTTP client injectable. Asserting on the `Request` the SDK would have sent is the only
 * way to test the properties that matter — that a retry reuses its idempotency key, that a
 * `POST` the platform does not deduplicate is never replayed — because those are properties of
 * requests that are never made twice on a healthy server.
 *
 * Run against `dist/`, like every other suite here: what a user installs is the built output.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

const {
  PaymentFlow,
  PaymentFlowConfigurationError,
  AuthenticationError,
  PermissionError,
  InvalidRequestError,
  IdempotencyError,
  RateLimitError,
  ApiConnectionError,
  ApiError,
  PaymentFlowError,
} = await import('../dist/esm/index.js');

const API_KEY = 'sk_test_transport';

/** A `fetch` stub that records every call and replies from a queued script. */
function recorder(...replies) {
  const calls = [];
  let index = 0;
  const fetch = async (url, init) => {
    calls.push({ url, init });
    const reply = replies[Math.min(index, replies.length - 1)];
    index += 1;
    if (typeof reply === 'function') return reply(url, init);
    // Cloned, never returned directly: a Response body is a one-shot stream, so the second
    // attempt of a retried call would otherwise read an already-consumed body and the SDK
    // would report a connection error for a server that answered perfectly well.
    return reply.clone();
  };
  return { fetch, calls };
}

function json(body, { status = 200, headers = {} } = {}) {
  return new Response(body === undefined ? '' : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

function client(fetch, options = {}) {
  return new PaymentFlow({ apiKey: API_KEY, baseUrl: 'https://api.test', fetch, ...options });
}

// ── Configuration ───────────────────────────────────────────────────────────────────────

test('a client cannot be built without an API key', () => {
  const previous = process.env.PAYMENTFLOW_API_KEY;
  delete process.env.PAYMENTFLOW_API_KEY;
  try {
    assert.throws(() => new PaymentFlow(), PaymentFlowConfigurationError);
  } finally {
    if (previous !== undefined) process.env.PAYMENTFLOW_API_KEY = previous;
  }
});

test('the API key is read from the environment when it is not passed', () => {
  const previous = process.env.PAYMENTFLOW_API_KEY;
  process.env.PAYMENTFLOW_API_KEY = 'sk_test_from_env';
  try {
    assert.equal(new PaymentFlow().config.apiKey, 'sk_test_from_env');
  } finally {
    if (previous === undefined) delete process.env.PAYMENTFLOW_API_KEY;
    else process.env.PAYMENTFLOW_API_KEY = previous;
  }
});

test('options that cannot work are rejected where they are set, not on the first call', () => {
  // A key that survived a copy-paste with a trailing newline produces a 401 that reads exactly
  // like a revoked key, hours later, in production.
  assert.throws(() => new PaymentFlow({ apiKey: 'sk_test_x\n' }), PaymentFlowConfigurationError);
  assert.throws(() => new PaymentFlow({ apiKey: API_KEY, baseUrl: 'not a url' }), PaymentFlowConfigurationError);
  assert.throws(() => new PaymentFlow({ apiKey: API_KEY, timeout: 0 }), PaymentFlowConfigurationError);
  assert.throws(() => new PaymentFlow({ apiKey: API_KEY, maxRetries: -1 }), PaymentFlowConfigurationError);
});

// ── What goes on the wire ───────────────────────────────────────────────────────────────

test('every request carries the key, the revision and the SDK identity', async () => {
  const { fetch, calls } = recorder(json({ id: 'pay_1' }));
  const sdk = client(fetch);

  await sdk.payments.retrieve('pay_1');

  const { headers } = calls[0].init;
  assert.equal(headers.Authorization, `Bearer ${API_KEY}`);
  assert.equal(headers['PaymentFlow-Version'], sdk.config.apiVersion);
  assert.match(headers['User-Agent'], /^paymentflow-node\/\d+\.\d+\.\d+ node\//);
  assert.equal(calls[0].url, 'https://api.test/v1/payments/pay_1');
  assert.equal(calls[0].init.method, 'GET');
});

test('a correlation id supplied by the caller is sent, and one is not invented otherwise', async () => {
  const { fetch, calls } = recorder(json({ id: 'pay_1' }), json({ id: 'pay_1' }));
  const sdk = client(fetch);

  await sdk.payments.retrieve('pay_1', undefined, { correlationId: 'trace-42' });
  assert.equal(calls[0].init.headers['X-Correlation-Id'], 'trace-42');

  // Omitted rather than filled in with a client-side guess: the platform generates one and
  // returns it, and two sources for one identifier is how they end up disagreeing.
  await sdk.payments.retrieve('pay_1');
  assert.equal(calls[1].init.headers['X-Correlation-Id'], undefined);
});

test('path parameters are substituted and escaped', async () => {
  const { fetch, calls } = recorder(json({ id: 'x' }));
  await client(fetch).events.retrieve('evt/../admin');

  assert.equal(calls[0].url, 'https://api.test/v1/events/evt%2F..%2Fadmin');
});

test('a missing path parameter fails before anything is sent', async () => {
  const { fetch, calls } = recorder(json({}));
  await assert.rejects(() => client(fetch).payments.retrieve(''), PaymentFlowError);

  assert.equal(calls.length, 0, 'nothing reached the network');
});

test('query parameters are checked against the operation the contract published', async () => {
  const { fetch, calls } = recorder(json({ data: [] }));
  const sdk = client(fetch);

  // The platform ignores a filter it does not recognise and returns an unfiltered page, which
  // looks exactly like a correct answer to a narrower question. Caught at the call instead.
  await assert.rejects(() => sdk.payments.list({ statuses: 'captured' }), /not a query parameter/);
  assert.equal(calls.length, 0);
});

test('a metadata filter is encoded as deepObject, and an array repeats its name', async () => {
  const { fetch, calls } = recorder(json({ data: [] }), json({ content: [], last: true }));
  const sdk = client(fetch);

  await sdk.payments.list({ metadata: { orderId: 'A-1234', channel: 'web' }, limit: 5 });
  const url = new URL(calls[0].url);
  assert.equal(url.searchParams.get('metadata[orderId]'), 'A-1234');
  assert.equal(url.searchParams.get('metadata[channel]'), 'web');
  assert.equal(url.searchParams.get('limit'), '5');

  await sdk.webhookDeliveries.list({ sort: ['createdAt,desc', 'id,asc'] });
  assert.deepEqual(new URL(calls[1].url).searchParams.getAll('sort'), ['createdAt,desc', 'id,asc']);
});

test('a 204 resolves with no body rather than failing to parse one', async () => {
  const { fetch } = recorder(new Response(null, { status: 204 }));

  assert.equal(await client(fetch).webhookEndpoints.del('we_1'), undefined);
});

test('unknown response fields and unknown enum values ride through untouched', async () => {
  const { fetch } = recorder(json({ id: 'pay_1', status: 'quantum_pending', somethingNew: { a: 1 } }));

  // §9's forward-compatibility promise: additive changes ship without a new revision, so an
  // SDK that validated responses would turn the platform's safest change into an outage.
  const payment = await client(fetch).payments.retrieve('pay_1');
  assert.equal(payment.status, 'quantum_pending');
  assert.deepEqual(payment.somethingNew, { a: 1 });
});

// ── Idempotency ─────────────────────────────────────────────────────────────────────────

test('a mutation the contract requires a key for gets one, and a read does not', async () => {
  const { fetch, calls } = recorder(json({ id: 'pay_1' }, { status: 201 }), json({ id: 'pay_1' }));
  const sdk = client(fetch);

  await sdk.payments.create({ amountMinor: 1000, currency: 'USD' });
  assert.match(calls[0].init.headers['Idempotency-Key'], /^[0-9a-f-]{36}$/);

  await sdk.payments.retrieve('pay_1');
  assert.equal(calls[1].init.headers['Idempotency-Key'], undefined);
});

test('a caller-supplied key wins over the generated one', async () => {
  const { fetch, calls } = recorder(json({ id: 'pay_1' }, { status: 201 }));

  await client(fetch).payments.create({ amountMinor: 1000, currency: 'USD' }, { idempotencyKey: 'order-7' });
  assert.equal(calls[0].init.headers['Idempotency-Key'], 'order-7');
});

test('a retried mutation reuses the key of the attempt it is retrying', async () => {
  const { fetch, calls } = recorder(json({ message: 'nope' }, { status: 500 }), json({ id: 'pay_1' }, { status: 201 }));

  const payment = await client(fetch, { maxRetries: 2 }).payments.create({ amountMinor: 1000, currency: 'USD' });

  // The property this SDK exists to hold. A key regenerated per attempt makes the platform
  // treat the retry as a new request, and the customer is charged twice — under exactly the
  // network conditions that cause retries, which is to say never in a hand-written test.
  assert.equal(calls.length, 2);
  assert.equal(calls[0].init.headers['Idempotency-Key'], calls[1].init.headers['Idempotency-Key']);
  assert.equal(payment.id, 'pay_1');
});

// ── Retries ─────────────────────────────────────────────────────────────────────────────

test('a 500 on a read is retried up to the budget and then raised', async () => {
  const { fetch, calls } = recorder(json({ message: 'boom' }, { status: 500 }));

  const error = await client(fetch, { maxRetries: 2 }).payments.retrieve('pay_1').catch((e) => e);

  assert.ok(error instanceof ApiError);
  assert.equal(calls.length, 3, 'the first attempt plus two retries');
  assert.equal(error.attempts, 3);
});

test('a 4xx that is not a rate limit is never retried', async () => {
  const { fetch, calls } = recorder(json({ type: 'invalid_request_error', message: 'no' }, { status: 400 }));

  await assert.rejects(() => client(fetch, { maxRetries: 3 }).payments.retrieve('pay_1'), InvalidRequestError);
  assert.equal(calls.length, 1, 'retrying only delays the error the caller needs to see');
});

test('a POST the platform does not deduplicate is not replayed', async () => {
  const { fetch, calls } = recorder(json({ message: 'boom' }, { status: 503 }));
  const sdk = client(fetch, { maxRetries: 3 });

  // `POST /v1/webhook_endpoints` requires no Idempotency-Key, so the platform has no way to
  // recognise a replay. A response that never arrived does not mean a request that never
  // arrived, so retrying could leave two endpoints where the caller asked for one.
  await assert.rejects(() => sdk.webhookEndpoints.create({ url: 'https://x.test', enabledEvents: ['a'] }), ApiError);
  assert.equal(calls.length, 1);
});

test('Retry-After is honoured over the computed backoff', async () => {
  const { fetch, calls } = recorder(
    json({ type: 'rate_limit_error', code: 'RATE_LIMIT_EXCEEDED' }, { status: 429, headers: { 'Retry-After': '0' } }),
    json({ id: 'pay_1' }),
  );

  const payment = await client(fetch, { maxRetries: 1 }).payments.retrieve('pay_1');
  assert.equal(calls.length, 2);
  assert.equal(payment.id, 'pay_1');
});

test('a Retry-After longer than the SDK will wait ends the call instead of hanging', async () => {
  const secondsUntilMidnight = 40_000;
  const { fetch, calls } = recorder(
    json(
      { type: 'rate_limit_error', code: 'DAILY_QUOTA_EXCEEDED', message: 'quota exhausted' },
      { status: 429, headers: { 'Retry-After': String(secondsUntilMidnight), 'RateLimit-Reset': String(secondsUntilMidnight) } },
    ),
  );

  const error = await client(fetch, { maxRetries: 3 }).payments.retrieve('pay_1').catch((e) => e);

  // The daily quota clears at 00:00 UTC. Sleeping that out inside a caller's request handler
  // is not honouring the header, it is a hang — so the SDK surrenders and hands back the
  // interval, which is what a caller needs in order to schedule the work (D168).
  assert.ok(error instanceof RateLimitError);
  assert.equal(error.retryAfterSeconds, secondsUntilMidnight);
  assert.equal(calls.length, 1, 'it did not sleep, and it did not silently ignore the header');
});

test('RateLimit-Reset is reported as telemetry and never used as a delay', async () => {
  const { fetch } = recorder(
    json({ id: 'pay_1' }, { headers: { 'RateLimit-Limit': '5000', 'RateLimit-Remaining': '4999', 'RateLimit-Reset': '43200' } }),
  );

  // It describes the *daily* window and is present on successful responses too, so treating
  // it as "wait this long" would idle a perfectly healthy client until midnight (D167).
  const sdk = client(fetch);
  const started = Date.now();
  await sdk.payments.retrieve('pay_1');
  assert.ok(Date.now() - started < 1_000);
});

test('a network failure is retried, and reported as a connection error when it persists', async () => {
  let attempts = 0;
  const fetch = async () => {
    attempts += 1;
    throw new TypeError('fetch failed');
  };

  const error = await client(fetch, { maxRetries: 1 }).payments.retrieve('pay_1').catch((e) => e);

  assert.ok(error instanceof ApiConnectionError);
  assert.equal(attempts, 2);
  assert.equal(error.statusCode, undefined, 'there was no response to have a status');
});

test('a request that outlives its timeout is abandoned', async () => {
  const fetch = (_url, init) =>
    new Promise((_resolve, reject) => {
      init.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
    });

  const error = await client(fetch, { timeout: 20, maxRetries: 0 }).payments.retrieve('pay_1').catch((e) => e);

  assert.ok(error instanceof ApiConnectionError);
  assert.match(error.message, /timed out after 20ms/);
});

// ── Errors ──────────────────────────────────────────────────────────────────────────────

test('the error class comes from ApiError.type rather than from the status', async () => {
  const cases = [
    ['authentication_error', 401, AuthenticationError],
    ['permission_error', 403, PermissionError],
    ['invalid_request_error', 400, InvalidRequestError],
    ['idempotency_error', 409, IdempotencyError],
    ['rate_limit_error', 429, RateLimitError],
    ['api_error', 500, ApiError],
  ];

  for (const [type, status, expected] of cases) {
    const { fetch } = recorder(json({ type, message: 'x', code: 'C' }, { status }));
    const error = await client(fetch, { maxRetries: 0 }).payments.retrieve('p').catch((e) => e);
    assert.ok(error instanceof expected, `${type} maps to ${expected.name}`);
  }
});

test('a 409 that is an idempotency conflict is distinguishable from one that is not', async () => {
  // Both are 409. One may succeed on a later attempt because a concurrent request is holding
  // the key; the other never will, whatever the caller does. Mapping on status alone would
  // throw away the only signal that tells them apart.
  const conflict = recorder(json({ type: 'idempotency_error', code: 'IDEMPOTENCY_CONFLICT' }, { status: 409 }));
  const terminal = recorder(json({ type: 'invalid_request_error', code: 'PAYMENT_NOT_CAPTURABLE' }, { status: 409 }));

  const a = await client(conflict.fetch, { maxRetries: 0 }).payments.capture('p').catch((e) => e);
  const b = await client(terminal.fetch, { maxRetries: 0 }).payments.capture('p').catch((e) => e);

  assert.ok(a instanceof IdempotencyError);
  assert.ok(b instanceof InvalidRequestError);
  assert.equal(b instanceof IdempotencyError, false);
});

test('an unrecognised error type falls back to the status instead of failing to parse', async () => {
  const { fetch } = recorder(json({ type: 'teapot_error', message: 'new in a later revision' }, { status: 403 }));

  // §9 lets new error types ship without a new API revision. An SDK that threw
  // "unknown error type" would make the platform's safest change an incident everywhere.
  const error = await client(fetch, { maxRetries: 0 }).payments.retrieve('p').catch((e) => e);
  assert.ok(error instanceof PermissionError);
  assert.equal(error.type, 'teapot_error');
});

test('an error body that is not the error contract still produces a usable error', async () => {
  const { fetch } = recorder(new Response('<html>502 Bad Gateway</html>', { status: 502 }));

  // A load balancer that never reached this platform writes whatever it likes. An error
  // constructor that can itself fail replaces a diagnosable failure with an undiagnosable one.
  const error = await client(fetch, { maxRetries: 0 }).payments.retrieve('p').catch((e) => e);
  assert.ok(error instanceof ApiError);
  assert.equal(error.statusCode, 502);
  assert.match(error.message, /502/);
});

test('an error carries everything the caller needs to report it', async () => {
  const { fetch } = recorder(
    json(
      {
        type: 'invalid_request_error',
        code: 'AMOUNT_TOO_SMALL',
        message: 'Amount must be positive.',
        param: 'amountMinor',
        requestId: 'req_from_body',
        correlationId: 'corr_1',
        docUrl: 'https://docs.test/AMOUNT_TOO_SMALL',
        errors: [{ field: 'amountMinor', message: 'must be positive' }],
      },
      { status: 400, headers: { 'X-Request-Id': 'req_from_header' } },
    ),
  );

  const error = await client(fetch, { maxRetries: 0 }).payments.create({ amountMinor: 0, currency: 'USD' }).catch((e) => e);

  assert.equal(error.code, 'AMOUNT_TOO_SMALL');
  assert.equal(error.param, 'amountMinor');
  assert.equal(error.docUrl, 'https://docs.test/AMOUNT_TOO_SMALL');
  assert.equal(error.correlationId, 'corr_1');
  assert.deepEqual(error.fieldErrors, [{ field: 'amountMinor', message: 'must be positive' }]);
  // The body's own requestId wins: it is the value the platform wrote for this failure.
  assert.equal(error.requestId, 'req_from_body');
});

// ── Response metadata ───────────────────────────────────────────────────────────────────

test('a successful call can be traced, which needed a platform fix to be true', async () => {
  const { fetch } = recorder(
    json(
      { data: [{ id: 'pay_1' }], hasMore: false },
      {
        headers: {
          'X-Request-Id': 'req_success',
          'X-Correlation-Id': 'corr_success',
          'PaymentFlow-Version': '2026-08-01',
          'RateLimit-Limit': '5000',
          'RateLimit-Remaining': '4998',
          'RateLimit-Reset': '3600',
        },
      },
    ),
  );

  // `X-Request-Id` was sent downstream and never returned, so before M22.2 a caller could
  // learn it only from an error body — and it is what keys every row of their own request log.
  const page = await client(fetch).payments.list();
  assert.equal(page.meta.requestId, 'req_success');
  assert.equal(page.meta.correlationId, 'corr_success');
  assert.equal(page.meta.apiVersion, '2026-08-01');
  assert.equal(page.meta.deprecated, false);
  assert.deepEqual(page.meta.rateLimit, { limit: 5000, remaining: 4998, resetSeconds: 3600 });
  assert.equal(page.meta.attempts, 1);
});

test('a superseded revision is reported as deprecated', async () => {
  const { fetch } = recorder(
    json({ data: [], hasMore: false }, { headers: { Deprecation: 'true', Sunset: 'Wed, 01 Aug 2027 00:00:00 GMT' } }),
  );

  assert.equal((await client(fetch).payments.list()).meta.deprecated, true);
});
