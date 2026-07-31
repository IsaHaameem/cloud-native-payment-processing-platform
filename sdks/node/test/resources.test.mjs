/*
 * The resource namespaces (M22.3), and the pagination they hand back.
 *
 * The assertion running through this file is that every method maps to exactly one published
 * operation and performs exactly one HTTP request. "Do not perform hidden HTTP requests" is
 * not a style preference for a payments SDK — a convenience method that quietly makes a second
 * chargeable call is one whose failure modes an integrator cannot reason about.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const { PaymentFlow } = await import('../dist/esm/index.js');
const { OPERATIONS } = await import('../dist/esm/generated/operations.js');

const fixtures = join(dirname(fileURLToPath(import.meta.url)), '../../shared/fixtures');

function recorder(...replies) {
  const calls = [];
  let index = 0;
  const fetch = async (url, init) => {
    calls.push({ url, init, method: init.method });
    const reply = replies[Math.min(index, replies.length - 1)];
    index += 1;
    // Cloned, never returned directly: a Response body is a one-shot stream, so a reply
    // reused across attempts would come back already consumed.
    return reply.clone();
  };
  return { fetch, calls };
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function client(fetch) {
  return new PaymentFlow({ apiKey: 'sk_test_resources', baseUrl: 'https://api.test', fetch });
}

// ── Coverage ────────────────────────────────────────────────────────────────────────────

test('every published operation is reachable from a resource namespace', async () => {
  const source = await Promise.all(
    [
      'payments',
      'refunds',
      'balance',
      'events',
      'reporting',
      'webhooks',
      'test-helpers',
    ].map((name) => readFile(join(dirname(fileURLToPath(import.meta.url)), `../src/resources/${name}.ts`), 'utf8')),
  );
  const combined = source.join('\n');

  // Derived from the generated table rather than from a list written here, so an endpoint
  // added to the platform shows up as a failure rather than as an SDK that quietly cannot
  // call it. This is the check that makes "implement every approved namespace" verifiable.
  const missing = Object.keys(OPERATIONS).filter((id) => !combined.includes(`OPERATIONS.${id}`));
  assert.deepEqual(missing, [], 'operations no resource method calls');

  // And the fixtures agree about how many there are, so this cannot pass over an empty table.
  const contract = JSON.parse(await readFile(join(fixtures, 'contract.json'), 'utf8'));
  assert.equal(Object.keys(OPERATIONS).length, contract.operationCount);
});

test('every resource method makes exactly one request, to the operation it names', async () => {
  // One call per method, checked against the descriptor rather than against a hand-written
  // URL: a method wired to the wrong operation would otherwise pass a test that transcribed
  // the same mistake.
  const cases = [
    ['payments.create', (c) => c.payments.create({ amountMinor: 1, currency: 'USD' }), OPERATIONS.createPayment],
    ['payments.retrieve', (c) => c.payments.retrieve('p'), OPERATIONS.getPayment],
    ['payments.authorize', (c) => c.payments.authorize('p'), OPERATIONS.authorizePayment],
    ['payments.capture', (c) => c.payments.capture('p'), OPERATIONS.capturePayment],
    ['payments.refund', (c) => c.payments.refund('p'), OPERATIONS.refundPayment],
    ['payments.void', (c) => c.payments.void('p'), OPERATIONS.voidPayment],
    ['refunds.retrieve', (c) => c.refunds.retrieve('r'), OPERATIONS.getRefund],
    ['balance.retrieve', (c) => c.balance.retrieve(), OPERATIONS.getBalance],
    ['events.retrieve', (c) => c.events.retrieve('e'), OPERATIONS.getEvent],
    ['analytics.retrievePaymentSummary', (c) => c.analytics.retrievePaymentSummary(), OPERATIONS.getPaymentAnalytics],
    ['usage.retrieve', (c) => c.usage.retrieve(), OPERATIONS.getUsage],
    ['webhookEndpoints.create', (c) => c.webhookEndpoints.create({ url: 'https://x.test', enabledEvents: [] }), OPERATIONS.createWebhookEndpoint],
    ['webhookEndpoints.retrieve', (c) => c.webhookEndpoints.retrieve('we'), OPERATIONS.getWebhookEndpoint],
    ['webhookEndpoints.list', (c) => c.webhookEndpoints.list(), OPERATIONS.listWebhookEndpoints],
    ['webhookEndpoints.update', (c) => c.webhookEndpoints.update('we', { enabled: false }), OPERATIONS.updateWebhookEndpoint],
    ['webhookEndpoints.del', (c) => c.webhookEndpoints.del('we'), OPERATIONS.deleteWebhookEndpoint],
    ['webhookEndpoints.rotateSecret', (c) => c.webhookEndpoints.rotateSecret('we'), OPERATIONS.rotateWebhookEndpointSecret],
    ['webhookDeliveries.retrieve', (c) => c.webhookDeliveries.retrieve('wd'), OPERATIONS.getWebhookDelivery],
    ['webhookDeliveries.replay', (c) => c.webhookDeliveries.replay('wd'), OPERATIONS.replayWebhookDelivery],
    ['testHelpers.listCards', (c) => c.testHelpers.listCards(), OPERATIONS.listTestCards],
    ['testHelpers.listDecisionsForPayment', (c) => c.testHelpers.listDecisionsForPayment('p'), OPERATIONS.listSandboxDecisionsForPayment],
    ['testHelpers.createSimulationOverride', (c) => c.testHelpers.createSimulationOverride({ scenario: 'DECLINE' }), OPERATIONS.createSimulationOverride],
    ['testHelpers.retrieveActiveSimulationOverride', (c) => c.testHelpers.retrieveActiveSimulationOverride(), OPERATIONS.getActiveSimulationOverride],
    ['testHelpers.revokeActiveSimulationOverride', (c) => c.testHelpers.revokeActiveSimulationOverride(), OPERATIONS.revokeActiveSimulationOverride],
  ];

  for (const [name, invoke, descriptor] of cases) {
    const { fetch, calls } = recorder(json({}));
    await invoke(client(fetch));

    assert.equal(calls.length, 1, `${name} makes exactly one request`);
    assert.equal(calls[0].method, descriptor.method, `${name} uses the documented method`);

    const path = new URL(calls[0].url).pathname;
    const template = descriptor.path.replace(/\{\w+}/g, '[^/]+');
    assert.match(path, new RegExp(`^${template}$`), `${name} calls ${descriptor.path}`);
  }
});

test('a body is sent only where the contract says the operation takes one', async () => {
  for (const [invoke, descriptor] of [
    [(c) => c.payments.create({ amountMinor: 1, currency: 'USD' }), OPERATIONS.createPayment],
    [(c) => c.payments.refund('p', { amountMinor: 1 }), OPERATIONS.refundPayment],
    [(c) => c.payments.capture('p'), OPERATIONS.capturePayment],
    [(c) => c.webhookEndpoints.rotateSecret('we'), OPERATIONS.rotateWebhookEndpointSecret],
  ]) {
    const { fetch, calls } = recorder(json({}));
    await invoke(client(fetch));

    const sent = calls[0].init.body !== undefined;
    assert.equal(sent, descriptor.hasRequestBody, `${descriptor.id} body presence matches the contract`);
    if (sent) {
      assert.equal(calls[0].init.headers['Content-Type'], 'application/json');
    }
  }
});

// ── Return shapes ───────────────────────────────────────────────────────────────────────

test('a method returns exactly the resource the API returned', async () => {
  const body = { id: 'pay_1', status: 'captured', amountMinor: 1000, refunds: [{ id: 're_1' }] };
  const { fetch } = recorder(json(body));

  // No envelope, no `data` wrapper, no derived fields. What the API returned is what a caller
  // can match against the documentation they read.
  assert.deepEqual(await client(fetch).payments.retrieve('pay_1'), body);
});

test('refunding a payment returns the payment, because that is what the endpoint returns', async () => {
  const { fetch } = recorder(json({ id: 'pay_1', object: 'payment', refunds: [{ id: 're_1' }] }));

  // Reshaping this into the refund would need either a second request or a guess about which
  // element of `refunds` is the new one. Both are worse than returning what arrived.
  const result = await client(fetch).payments.refund('pay_1', { amountMinor: 500 });
  assert.equal(result.object, 'payment');
});

test('an unpaginated list is an array, not an invented page', async () => {
  const { fetch } = recorder(json([{ id: 'we_1' }, { id: 'we_2' }]));

  // `/v1/webhook_endpoints` returns a bare array. Wrapping it would mean inventing a
  // `hasMore` that no response carries.
  const endpoints = await client(fetch).webhookEndpoints.list();
  assert.ok(Array.isArray(endpoints));
  assert.equal(endpoints.length, 2);
});

// ── Pagination ──────────────────────────────────────────────────────────────────────────

test('a cursor list walks every page without the caller handling a cursor', async () => {
  const { fetch, calls } = recorder(
    json({ data: [{ id: 'pay_1' }, { id: 'pay_2' }], hasMore: true, nextCursor: 'cur_1' }),
    json({ data: [{ id: 'pay_3' }], hasMore: false }),
  );

  const seen = [];
  for await (const payment of await client(fetch).payments.list({ status: 'captured' })) {
    seen.push(payment.id);
  }

  assert.deepEqual(seen, ['pay_1', 'pay_2', 'pay_3']);
  assert.equal(calls.length, 2);
  // The second page carries the cursor *and* the original filter. Re-issuing a page request
  // with different filters than the cursor was minted under returns a result set that never
  // existed.
  const second = new URL(calls[1].url).searchParams;
  assert.equal(second.get('starting_after'), 'cur_1');
  assert.equal(second.get('status'), 'captured');
});

test('a caller who stops iterating stops making requests', async () => {
  const { fetch, calls } = recorder(
    json({ data: [{ id: 'pay_1' }], hasMore: true, nextCursor: 'cur_1' }),
    json({ data: [{ id: 'pay_2' }], hasMore: false }),
  );

  for await (const payment of await client(fetch).payments.list()) {
    assert.equal(payment.id, 'pay_1');
    break;
  }

  assert.equal(calls.length, 1, 'breaking out of the loop does not fetch the rest of the account');
});

test('a page also exposes the manual controls, for a caller who wants them', async () => {
  const { fetch } = recorder(
    json({ data: [{ id: 'pay_1' }], hasMore: true, nextCursor: 'cur_1' }),
    json({ data: [{ id: 'pay_2' }], hasMore: false }),
  );

  const first = await client(fetch).payments.list();
  assert.deepEqual(first.data, [{ id: 'pay_1' }]);
  assert.equal(first.hasMore, true);
  assert.equal(first.nextCursor, 'cur_1');

  const second = await first.nextPage();
  assert.deepEqual(second.data, [{ id: 'pay_2' }]);
  assert.equal(second.hasMore, false);
  assert.equal(await second.nextPage(), undefined);
});

test('an offset list paginates by page index and reports the totals it actually has', async () => {
  const { fetch, calls } = recorder(
    json({ content: [{ id: 'wd_1' }], page: 0, size: 1, totalElements: 2, totalPages: 2, last: false }),
    json({ content: [{ id: 'wd_2' }], page: 1, size: 1, totalElements: 2, totalPages: 2, last: true }),
  );

  const seen = [];
  const first = await client(fetch).webhookDeliveries.list({ size: 1 });
  assert.equal(first.totalElements, 2, 'the offset envelope does carry a total, unlike a cursor page');
  for await (const delivery of first) {
    seen.push(delivery.id);
  }

  assert.deepEqual(seen, ['wd_1', 'wd_2']);
  assert.equal(new URL(calls[0].url).searchParams.get('page'), '0');
  assert.equal(new URL(calls[1].url).searchParams.get('page'), '1');
});

test('a cursor page with a cursor and no flag is not treated as the last one', async () => {
  const { fetch, calls } = recorder(
    json({ data: [{ id: 'pay_1' }], nextCursor: 'cur_1' }),
    json({ data: [{ id: 'pay_2' }], hasMore: false }),
  );

  const seen = [];
  for await (const payment of await client(fetch).payments.list()) seen.push(payment.id);

  // Defaulting `hasMore` to false would silently truncate the result — the exact failure the
  // pagination helpers exist to prevent, and one that looks correct on a small account.
  assert.deepEqual(seen, ['pay_1', 'pay_2']);
  assert.equal(calls.length, 2);
});
