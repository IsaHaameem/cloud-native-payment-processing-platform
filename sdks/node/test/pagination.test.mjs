/*
 * Pagination, checked for *consistency* rather than one endpoint at a time (M22.4).
 *
 * M22.3 tested that payments and webhook deliveries paginate. What this file adds is the
 * property that matters once there are eight list methods: that they all behave the same way.
 * A caller who learns `for await` on payments and finds that refunds needs something else has
 * been given two APIs, and the one they guess at is the one that silently returns page one.
 *
 * Every list method is driven here, from a table, so a new one added without pagination fails
 * rather than being noticed later.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

const { PaymentFlow } = await import('../dist/esm/index.js');

function client(fetch) {
  return new PaymentFlow({ apiKey: 'sk_test_pagination', baseUrl: 'https://api.test', fetch });
}

function json(body) {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

/** Replies from a script, cloning so a body can be read more than once. */
function scripted(...replies) {
  const calls = [];
  let index = 0;
  const fetch = async (url, init) => {
    calls.push({ url, init });
    const reply = replies[Math.min(index, replies.length - 1)];
    index += 1;
    return reply.clone();
  };
  return { fetch, calls };
}

/** Every cursor-paginated list on the client, with the filter it was called with. */
const CURSOR_LISTS = [
  ['payments', (c) => c.payments.list({ status: 'captured' })],
  ['refunds', (c) => c.refunds.list({ payment: 'pay_1' })],
  ['events', (c) => c.events.list({ type: 'payment.captured' })],
  ['balanceTransactions', (c) => c.balanceTransactions.list({ limit: 25 })],
  ['requestLogs', (c) => c.requestLogs.list({ status_code: 200 })],
];

/** Every offset-paginated list — the two D139 left on the older envelope. */
const OFFSET_LISTS = [
  ['webhookDeliveries', (c) => c.webhookDeliveries.list({ size: 1 })],
  ['testHelpers.listDecisions', (c) => c.testHelpers.listDecisions({ size: 1 })],
];

/** The lists that are not paginated on the wire and must not pretend to be. */
const PLAIN_LISTS = [
  ['webhookEndpoints', (c) => c.webhookEndpoints.list()],
  ['testHelpers.listCards', (c) => c.testHelpers.listCards()],
  ['testHelpers.listDecisionsForPayment', (c) => c.testHelpers.listDecisionsForPayment('pay_1')],
];

// ── Coverage ────────────────────────────────────────────────────────────────────────────

test('every list method on the client is accounted for by one of the three tables', async () => {
  const sdk = client(async () => json({ data: [], hasMore: false }));

  const named = new Set([...CURSOR_LISTS, ...OFFSET_LISTS, ...PLAIN_LISTS].map(([name]) => name));
  const found = [];
  for (const [namespace, resource] of Object.entries(sdk)) {
    if (namespace === 'config' || typeof resource !== 'object') continue;
    for (const method of Object.getOwnPropertyNames(Object.getPrototypeOf(resource))) {
      if (method.startsWith('list')) {
        found.push(method === 'list' ? namespace : `${namespace}.${method}`);
      }
    }
  }

  // Derived from the client rather than transcribed, so a list method added without a decision
  // about how it paginates shows up here as a failure.
  assert.deepEqual(found.filter((name) => !named.has(name)), [], 'list methods with no pagination test');
  assert.ok(found.length >= 9, `expected every list method to be found, saw ${found.length}`);
});

// ── Cursor lists ────────────────────────────────────────────────────────────────────────

test('every cursor list iterates across pages, carrying its filters', async () => {
  for (const [name, invoke] of CURSOR_LISTS) {
    const { fetch, calls } = scripted(
      json({ data: [{ id: 'a' }, { id: 'b' }], hasMore: true, nextCursor: 'cur_1' }),
      json({ data: [{ id: 'c' }], hasMore: false }),
    );

    const seen = [];
    for await (const item of await invoke(client(fetch))) seen.push(item.id);

    assert.deepEqual(seen, ['a', 'b', 'c'], `${name} walks both pages`);
    assert.equal(calls.length, 2, `${name} fetched exactly two pages`);

    const second = new URL(calls[1].url).searchParams;
    assert.equal(second.get('starting_after'), 'cur_1', `${name} sends the cursor`);

    // Whatever filter the first call carried must be on the second. A page fetched with
    // different filters than the cursor was minted under returns a set that never existed.
    for (const [key, value] of new URL(calls[0].url).searchParams) {
      assert.equal(second.get(key), value, `${name} keeps ${key} across pages`);
    }
  }
});

test('every cursor list exposes the same manual controls', async () => {
  for (const [name, invoke] of CURSOR_LISTS) {
    const { fetch } = scripted(
      json({ data: [{ id: 'a' }], hasMore: true, nextCursor: 'cur_1' }),
      json({ data: [{ id: 'b' }], hasMore: false }),
    );

    const page = await invoke(client(fetch));
    assert.ok(Array.isArray(page.data), `${name} exposes .data`);
    assert.equal(page.hasMore, true, `${name} exposes .hasMore`);
    assert.equal(page.nextCursor, 'cur_1', `${name} exposes .nextCursor`);
    assert.equal(typeof page.nextPage, 'function', `${name} exposes .nextPage()`);
    assert.equal(typeof page.meta.requestId, 'undefined', `${name} exposes .meta`);
    assert.equal(page.meta.statusCode, 200, `${name} reports the status it got`);

    const last = await page.nextPage();
    assert.equal(last.hasMore, false, `${name}'s second page is the last`);
    assert.equal(await last.nextPage(), undefined, `${name} ends by resolving undefined`);
  }
});

test('every cursor list stops making requests when the caller stops iterating', async () => {
  for (const [name, invoke] of CURSOR_LISTS) {
    const { fetch, calls } = scripted(
      json({ data: [{ id: 'a' }], hasMore: true, nextCursor: 'cur_1' }),
      json({ data: [{ id: 'b' }], hasMore: false }),
    );

    for await (const _item of await invoke(client(fetch))) break;
    assert.equal(calls.length, 1, `${name} does not fetch the rest of the account after a break`);
  }
});

test('every cursor list treats a page with a cursor and no flag as having more', async () => {
  for (const [name, invoke] of CURSOR_LISTS) {
    const { fetch, calls } = scripted(
      json({ data: [{ id: 'a' }], nextCursor: 'cur_1' }),
      json({ data: [{ id: 'b' }], hasMore: false }),
    );

    const seen = [];
    for await (const item of await invoke(client(fetch))) seen.push(item.id);

    // Defaulting to `false` would silently truncate — a failure that looks correct on a small
    // account and loses data on a large one.
    assert.deepEqual(seen, ['a', 'b'], `${name} does not stop early`);
    assert.equal(calls.length, 2);
  }
});

test('an empty cursor list iterates zero times without a second request', async () => {
  for (const [name, invoke] of CURSOR_LISTS) {
    const { fetch, calls } = scripted(json({ data: [], hasMore: false }));

    const seen = [];
    for await (const item of await invoke(client(fetch))) seen.push(item);

    assert.deepEqual(seen, [], `${name} yields nothing`);
    assert.equal(calls.length, 1, `${name} does not ask for a page that cannot exist`);
  }
});

// ── Offset lists ────────────────────────────────────────────────────────────────────────

test('every offset list iterates by page index and reports its totals', async () => {
  for (const [name, invoke] of OFFSET_LISTS) {
    const { fetch, calls } = scripted(
      json({ content: [{ id: 'a' }], page: 0, size: 1, totalElements: 2, totalPages: 2, last: false }),
      json({ content: [{ id: 'b' }], page: 1, size: 1, totalElements: 2, totalPages: 2, last: true }),
    );

    const page = await invoke(client(fetch));
    assert.equal(page.totalElements, 2, `${name} reports a total, unlike a cursor page`);
    assert.equal(page.totalPages, 2);
    assert.equal(page.page, 0);
    assert.equal(page.hasMore, true);
    assert.ok(Array.isArray(page.content), `${name} exposes .content`);

    const seen = [];
    for await (const item of page) seen.push(item.id);
    assert.deepEqual(seen, ['a', 'b'], `${name} walks both pages`);

    assert.equal(new URL(calls[0].url).searchParams.get('page'), '0');
    assert.equal(new URL(calls[1].url).searchParams.get('page'), '1');
  }
});

test('an offset list derives hasMore from the index when `last` is absent', async () => {
  for (const [name, invoke] of OFFSET_LISTS) {
    const { fetch } = scripted(
      json({ content: [{ id: 'a' }], page: 0, size: 1, totalElements: 2, totalPages: 2 }),
      json({ content: [{ id: 'b' }], page: 1, size: 1, totalElements: 2, totalPages: 2 }),
    );

    const page = await invoke(client(fetch));
    assert.equal(page.hasMore, true, `${name} works out that page 0 of 2 is not the last`);

    const second = await page.nextPage();
    assert.equal(second.hasMore, false, `${name} works out that page 1 of 2 is`);
    assert.equal(await second.nextPage(), undefined);
  }
});

test('an offset list honours a starting page index the caller asked for', async () => {
  const { fetch, calls } = scripted(
    json({ content: [{ id: 'c' }], page: 2, size: 1, totalElements: 3, totalPages: 3, last: true }),
  );

  await client(fetch).webhookDeliveries.list({ page: 2, size: 1 });
  assert.equal(new URL(calls[0].url).searchParams.get('page'), '2');
});

// ── The lists that are not pages ────────────────────────────────────────────────────────

test('an unpaginated list is a plain array with no page machinery bolted on', async () => {
  for (const [name, invoke] of PLAIN_LISTS) {
    const { fetch, calls } = scripted(json([{ id: 'a' }, { id: 'b' }]));

    const result = await invoke(client(fetch));
    assert.ok(Array.isArray(result), `${name} returns an array`);
    assert.equal(result.length, 2);
    assert.equal(result.hasMore, undefined, `${name} invents no hasMore`);
    assert.equal(result.nextPage, undefined, `${name} invents no nextPage`);
    assert.equal(calls.length, 1);
  }
});

// ── Both shapes at once ─────────────────────────────────────────────────────────────────

test('both page types satisfy the same iteration contract', async () => {
  const cursor = await client(async () => json({ data: [{ id: 'a' }], hasMore: false })).payments.list();
  const offset = await client(async () =>
    json({ content: [{ id: 'a' }], page: 0, size: 1, totalElements: 1, totalPages: 1, last: true }),
  ).webhookDeliveries.list();

  // The shared surface a caller can rely on without knowing which shape they were handed.
  for (const [name, page] of [['cursor', cursor], ['offset', offset]]) {
    assert.equal(typeof page[Symbol.asyncIterator], 'function', `${name} is async-iterable`);
    assert.equal(typeof page.hasMore, 'boolean', `${name} has a boolean hasMore`);
    assert.equal(typeof page.nextPage, 'function', `${name} has nextPage()`);
    assert.equal(typeof page.meta, 'object', `${name} has meta`);

    const seen = [];
    for await (const item of page) seen.push(item.id);
    assert.deepEqual(seen, ['a'], `${name} yields its one item`);
  }
});
