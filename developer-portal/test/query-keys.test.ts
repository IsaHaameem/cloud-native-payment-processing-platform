import { describe, expect, it } from 'vitest';

import { type QueryScope, queryKeys } from '@/lib/query/keys';
import { buildCrumbs } from '@/components/layout/breadcrumbs';
import { candidatesFor } from '@/lib/query/object-lookup';
import { readOperation, readOperationIds } from '@/lib/api/read-operations';
import { OPERATIONS } from '@/generated/operations';

/**
 * The data layer's pure parts (M23.3).
 *
 * Keys, the read allowlist, id classification and the breadcrumb trail are all decisions made
 * without a network or a browser, which is exactly why they are worth testing here rather than
 * only through a rendered page: each of them is a rule the rest of the portal is expected to obey
 * without restating it.
 */

const TEST: QueryScope = { merchantId: 'merchant-a', mode: 'test' };
const LIVE: QueryScope = { merchantId: 'merchant-a', mode: 'live' };
const OTHER: QueryScope = { merchantId: 'merchant-b', mode: 'test' };

describe('query keys carry the scope', () => {
  it('puts mode in every key', () => {
    // §6.6: "Mode is part of every query key so a switch can never serve cached cross-mode data."
    for (const key of [
      queryKeys.scope(TEST),
      queryKeys.operation(TEST, 'listPayments'),
      queryKeys.object(TEST, 'getPayment', 'pay-1'),
    ]) {
      expect(JSON.stringify(key)).toContain('"mode":"test"');
    }
  });

  it('separates the same query in the two modes', () => {
    // The failure this prevents: a merchant switches to live and the list re-renders from the
    // test-mode cache before the refetch lands.
    expect(queryKeys.operation(TEST, 'listPayments')).not.toEqual(
      queryKeys.operation(LIVE, 'listPayments'),
    );
    expect(queryKeys.object(TEST, 'getPayment', 'x')).not.toEqual(
      queryKeys.object(LIVE, 'getPayment', 'x'),
    );
  });

  it('separates the same query for two merchants', () => {
    // Inert today — one owner has one merchant — and the reason it is here anyway is M24's admin
    // console, which reads other merchants' objects for support.
    expect(queryKeys.operation(TEST, 'listPayments')).not.toEqual(
      queryKeys.operation(OTHER, 'listPayments'),
    );
  });

  it('separates the same operation under different arguments', () => {
    expect(queryKeys.operation(TEST, 'listPayments', { status: 'captured' })).not.toEqual(
      queryKeys.operation(TEST, 'listPayments', { status: 'refunded' }),
    );
  });

  it('starts every key with the scope, so one prefix invalidates one merchant-mode', () => {
    // TanStack matches structurally by prefix; this is the property `useInvalidatePlatform.scope`
    // depends on, and it is invisible unless asserted.
    const scopeKey = queryKeys.scope(TEST);
    for (const key of [
      queryKeys.operation(TEST, 'listPayments', { limit: 10 }),
      queryKeys.object(TEST, 'getPayment', 'pay-1'),
    ]) {
      expect(key[0]).toEqual(scopeKey[0]);
    }
  });

  it('keeps a detail key distinct from a list key for the same object', () => {
    // Distinct at the operation id, which is where it matters: invalidating the list must not
    // discard the detail, and vice versa.
    expect(queryKeys.object(TEST, 'getPayment', 'p1')).not.toEqual(
      queryKeys.operation(TEST, 'listPayments', {}),
    );
  });

  it('spells the same request the same way through either factory', () => {
    // `object` is ergonomics, not a second key shape. When this stopped being true — an earlier
    // draft gave it a discriminator — the two hooks would have cached one request twice.
    expect(queryKeys.object(TEST, 'getPayment', 'p1')).toEqual(
      queryKeys.operation(TEST, 'getPayment', { id: 'p1' }),
    );
  });
});

describe('the browser may only ask for reads', () => {
  it('admits every GET in the contract', () => {
    const gets = Object.values(OPERATIONS).filter((o) => o.method === 'GET');
    expect(readOperationIds().length).toBe(gets.length);
    expect(readOperationIds().length).toBeGreaterThan(5);
  });

  it('admits no mutation, by name or by method', () => {
    // The security property of `/api/platform/[operation]`: capture, refund and void stay behind
    // Server Actions with CSRF and confirmation dialogs. This asserts it from the descriptors
    // rather than from a list, so a mutation added to the contract cannot slip in.
    for (const id of readOperationIds()) {
      expect(OPERATIONS[id as keyof typeof OPERATIONS].method).toBe('GET');
    }
    for (const id of ['createPayment', 'capturePayment', 'refundPayment', 'voidPayment']) {
      expect(readOperation(id)).toBeUndefined();
    }
  });

  it('reports an unknown name and a mutation identically', () => {
    // Answering differently would tell the browser which mutations exist.
    expect(readOperation('capturePayment')).toBeUndefined();
    expect(readOperation('noSuchOperationAnywhere')).toBeUndefined();
    expect(readOperation(undefined)).toBeUndefined();
  });

  it('extracts path placeholders from the template', () => {
    expect(readOperation('getPayment')?.pathParameters).toEqual(['id']);
    expect(readOperation('listPayments')?.pathParameters).toEqual([]);
  });
});

describe('classifying a pasted id', () => {
  it('treats a UUID as a payment first, then a refund', () => {
    // Both are UUIDs in the contract and nothing in the string separates them, so the order is
    // the guess and the platform is the arbiter.
    const candidates = candidatesFor('edd2c111-7e7b-49c5-9fb4-5b6f63df7239');
    expect(candidates.map((c) => c.kind)).toEqual(['payment', 'refund']);
    expect(candidates[0]?.operationId).toBe('getPayment');
  });

  it('recognises an event id by its prefix', () => {
    const candidates = candidatesFor('evt_9f2c1e7a4b8d4c3e8a1d2b4f6a8c05d1');
    expect(candidates.map((c) => c.kind)).toEqual(['event']);
  });

  it('tolerates surrounding whitespace, because ids arrive pasted', () => {
    expect(candidatesFor('  edd2c111-7e7b-49c5-9fb4-5b6f63df7239 ')).toHaveLength(2);
  });

  it('does not treat ordinary typing as an id', () => {
    // The palette is mostly navigation; a request per keystroke would be the bug here.
    for (const input of ['', 'pay', 'payments', 'evt_', 'evt_short', 'not-a-uuid', '12345']) {
      expect(candidatesFor(input)).toHaveLength(0);
    }
  });

  it('rejects a prefixed id that is not the contract shape', () => {
    // `pay_…` is what the M23.1 note assumed and the contract does not use.
    expect(candidatesFor('pay_9f2c1e7a4b8d4c3e8a1d2b4f6a8c05d1')).toHaveLength(0);
  });
});

describe('the breadcrumb trail', () => {
  it('shows nothing for a one-level route', () => {
    // On /dashboard the trail would repeat the page title.
    expect(buildCrumbs('/dashboard')).toHaveLength(1);
  });

  it('takes its labels from the navigation, not from the URL', () => {
    // `/developers/api-keys` reads "API keys", which title-casing the segment could never produce.
    // The `/developers` prefix reads "Integration" — the name the nav group carries — via the
    // section-label override, not the title-cased URL segment.
    const crumbs = buildCrumbs('/developers/api-keys');
    expect(crumbs.map((c) => c.label)).toEqual(['Integration', 'API keys']);
  });

  it('does not link a section that is not a page', () => {
    expect(buildCrumbs('/developers/api-keys')[0]?.href).toBeUndefined();
  });

  it('truncates an object id rather than pushing the header off screen', () => {
    const crumbs = buildCrumbs('/payments/edd2c111-7e7b-49c5-9fb4-5b6f63df7239');
    expect(crumbs).toHaveLength(2);
    expect(crumbs[0]?.label).toBe('Payments');
    expect(crumbs[1]?.label).toContain('…');
    expect(crumbs[1]?.label.length).toBeLessThan(12);
  });

  it('leaves the last crumb unlinked, because it is the current page', () => {
    const crumbs = buildCrumbs('/payments/edd2c111-7e7b-49c5-9fb4-5b6f63df7239');
    expect(crumbs[crumbs.length - 1]?.href).toBeUndefined();
  });
});
