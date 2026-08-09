import { describe, expect, it } from 'vitest';

import { OPERATIONS } from '@/generated/operations';
import { declaresQueryParameter } from '@/lib/api/transport';
import { csvFilename, paymentsToCsv } from '@/lib/payments/csv';
import {
  NO_FILTERS,
  PAYMENT_STATUSES,
  activeFilterCount,
  filtersFromSearchParams,
  hasClientSideNarrowing,
  isFiltered,
  matchesClientSide,
  searchParamsFromFilters,
  toPlatformParams,
} from '@/lib/payments/filters';
import { queryKeys } from '@/lib/query/keys';

/**
 * The payments list (M23.6).
 *
 * Three properties are worth defending here and none of them is about rendering. The URL and the
 * filter object must round-trip, so a shared link is the view it claims to be. The parameters sent
 * to `listPayments` must be a subset of what the contract documents, because anything else is a
 * 400 from the read route or — worse, before that route existed — a correct-looking unfiltered
 * page. And the query key must carry the scope, because a key without it caches one merchant's
 * money under another's.
 */

const params = (query: string) => new URLSearchParams(query);

describe('reading filters from a URL', () => {
  it('is empty for an empty query string', () => {
    expect(filtersFromSearchParams(params(''))).toEqual(NO_FILTERS);
    expect(isFiltered(filtersFromSearchParams(params('')))).toBe(false);
  });

  it('reads every filter the list offers', () => {
    const filters = filtersFromSearchParams(
      params(
        'status=captured,failed&currency=eur&amount_min=100&amount_max=5000' +
          '&created_after=2026-08-01T00:00:00.000Z&created_before=2026-08-09T23:59:59.999Z' +
          '&metadata_key=order_id&metadata_value=ord_7',
      ),
    );

    expect(filters.statuses).toEqual(['captured', 'failed']);
    expect(filters.currency).toBe('EUR');
    expect(filters.amountMin).toBe(100);
    expect(filters.amountMax).toBe(5000);
    expect(filters.metadataKey).toBe('order_id');
    expect(filters.metadataValue).toBe('ord_7');
  });

  /**
   * Dropped, never corrected. A filter that silently means something other than what the URL says
   * misleads; one that is visibly absent does not.
   */
  it.each([
    ['amount_min=abc', 'amountMin'],
    ['amount_min=-5', 'amountMin'],
    ['amount_min=1.5', 'amountMin'],
    ['created_after=last-tuesday', 'createdAfter'],
    ['currency=EUROS', 'currency'],
  ])('drops a malformed %s rather than coercing it', (query, field) => {
    const filters = filtersFromSearchParams(params(query)) as unknown as Record<string, unknown>;
    expect(filters[field]).toBeUndefined();
  });

  it('treats a metadata key with no value as a real filter', () => {
    const filters = filtersFromSearchParams(params('metadata_key=order_id'));
    expect(filters.metadataKey).toBe('order_id');
    expect(filters.metadataValue).toBe('');
  });

  it('ignores a metadata value with no key', () => {
    expect(filtersFromSearchParams(params('metadata_value=orphan')).metadataKey).toBeUndefined();
  });

  /** The URL is the view: what goes in must come out, or a shared link is not what it showed. */
  it('round-trips every filter through the query string', () => {
    const original = filtersFromSearchParams(
      params(
        'status=captured,refunded&currency=USD&amount_min=1&amount_max=2' +
          '&created_after=2026-01-01T00:00:00.000Z&metadata_key=k&metadata_value=v',
      ),
    );

    expect(filtersFromSearchParams(searchParamsFromFilters(original))).toEqual(original);
  });

  it('writes nothing for an unfiltered view', () => {
    expect(searchParamsFromFilters(NO_FILTERS).toString()).toBe('');
  });

  it('counts a range as one filter, not two', () => {
    const filters = filtersFromSearchParams(params('amount_min=1&amount_max=2'));
    expect(activeFilterCount(filters)).toBe(1);
  });
});

describe('parameters sent to the platform', () => {
  const documented = OPERATIONS.listPayments.queryParameters as readonly string[];

  it('sends only parameters the contract documents', () => {
    const filters = filtersFromSearchParams(
      params(
        'status=captured&currency=USD&amount_min=1&amount_max=2' +
          '&created_after=2026-01-01T00:00:00.000Z&created_before=2026-02-01T00:00:00.000Z' +
          '&metadata_key=order_id&metadata_value=ord_1',
      ),
    );

    for (const name of Object.keys(toPlatformParams(filters, 25))) {
      expect(
        declaresQueryParameter(documented, name),
        `listPayments does not document "${name}"`,
      ).toBe(true);
    }
  });

  /**
   * The rule that keeps a shared link from being a way to ask for someone else's rows. Merchant
   * comes from the JWT and mode from the sealed cookie; neither is a parameter of this operation.
   */
  it.each(['mode', 'merchant', 'merchantId', 'merchant_id'])(
    'never sends %s, and the contract would not accept it',
    (name) => {
      const filters = filtersFromSearchParams(params('status=captured'));
      expect(Object.keys(toPlatformParams(filters, 25))).not.toContain(name);
      expect(declaresQueryParameter(documented, name)).toBe(false);
    },
  );

  it('spells a metadata filter the way the contract does', () => {
    const filters = filtersFromSearchParams(params('metadata_key=order_id&metadata_value=ord_7'));
    expect(toPlatformParams(filters, 25)['metadata[order_id]']).toBe('ord_7');
  });

  it('omits absent filters rather than sending empty values', () => {
    expect(toPlatformParams(NO_FILTERS, 25)).toEqual({ limit: 25 });
  });

  /**
   * The contract types `status` as one string. The first selection goes to the platform and the
   * rest narrow the rows that came back — which can only remove rows the platform already scoped
   * to this merchant and mode, never introduce one.
   */
  it('sends one status even when several are selected, and says so', () => {
    const filters = filtersFromSearchParams(params('status=captured,failed'));

    expect(toPlatformParams(filters, 25).status).toBe('captured');
    expect(hasClientSideNarrowing(filters)).toBe(true);
    expect(matchesClientSide(filters, { status: 'failed' })).toBe(true);
    expect(matchesClientSide(filters, { status: 'voided' })).toBe(false);
  });

  it('narrows nothing client-side when one status is selected', () => {
    const filters = filtersFromSearchParams(params('status=captured'));
    expect(hasClientSideNarrowing(filters)).toBe(false);
    expect(matchesClientSide(filters, { status: 'anything' })).toBe(true);
  });

  it('offers only statuses the payment FSM can produce', () => {
    expect([...PAYMENT_STATUSES]).toEqual([
      'created',
      'authorized',
      'captured',
      'partially_refunded',
      'refunded',
      'failed',
      'voided',
    ]);
    // The FSM has no partially-captured state; capture is all-or-nothing.
    expect(PAYMENT_STATUSES as readonly string[]).not.toContain('partially_captured');
  });
});

describe('the deepObject parameter rule', () => {
  const documented = OPERATIONS.listPayments.queryParameters as readonly string[];

  it('accepts a declared parameter by name', () => {
    expect(declaresQueryParameter(documented, 'status')).toBe(true);
  });

  it('accepts the bracket spelling of a declared map parameter', () => {
    expect(declaresQueryParameter(documented, 'metadata[order_id]')).toBe(true);
  });

  /** The check that turns a typo into an exception has to survive the widening. */
  it.each(['metadatum[x]', 'mode[x]', 'merchant[id]', 'stat[us]'])('still refuses %s', (name) => {
    expect(declaresQueryParameter(documented, name)).toBe(false);
  });

  it.each(['[x]', 'metadata[', 'metadata[x', 'metadata]x['])(
    'refuses the malformed bracket form %s',
    (name) => {
      expect(declaresQueryParameter(documented, name)).toBe(false);
    },
  );
});

describe('query keys', () => {
  const scope = { merchantId: 'merchant-1', mode: 'test' } as const;

  it('carries the merchant and the mode in every payments key', () => {
    const key = queryKeys.operation(scope, 'listPayments', { limit: 25 });
    expect(key[0]).toEqual({ merchantId: 'merchant-1', mode: 'test' });
  });

  /** The property that stops one mode's cache answering for the other. */
  it('produces a different key for the same filters in the other mode', () => {
    const test = queryKeys.operation(scope, 'listPayments', { limit: 25 });
    const live = queryKeys.operation({ ...scope, mode: 'live' }, 'listPayments', { limit: 25 });
    expect(test).not.toEqual(live);
  });

  it('produces a different key for a different merchant', () => {
    const mine = queryKeys.operation(scope, 'listPayments', { limit: 25 });
    const theirs = queryKeys.operation({ ...scope, merchantId: 'merchant-2' }, 'listPayments', {
      limit: 25,
    });
    expect(mine).not.toEqual(theirs);
  });

  it('produces a different key for different filters', () => {
    const all = queryKeys.operation(scope, 'listPayments', { limit: 25 });
    const captured = queryKeys.operation(scope, 'listPayments', { limit: 25, status: 'captured' });
    expect(all).not.toEqual(captured);
  });

  /**
   * Scope-wide invalidation is a prefix match on the scope segment, so it must be the *same*
   * value the operation key starts with. If these ever diverge, a mutation would refresh nothing.
   */
  it('starts an operation key with exactly the scope key', () => {
    const scopeKey = queryKeys.scope(scope);
    const operationKey = queryKeys.operation(scope, 'listPayments', {});
    expect(operationKey.slice(0, scopeKey.length)).toEqual([...scopeKey]);
  });
});

describe('CSV export', () => {
  const payment = {
    id: '11111111-aaaa-4bbb-8ccc-111111111111',
    status: 'captured',
    amountMinor: 4200,
    capturedAmountMinor: 4200,
    refundedAmountMinor: 0,
    currency: 'EUR',
    mode: 'test',
    createdAt: '2026-08-09T10:00:00Z',
  };

  it('writes a header and one row per payment', () => {
    const lines = paymentsToCsv([payment]).split('\r\n');
    expect(lines).toHaveLength(2);
    expect(lines[0]).toContain('amount_minor');
    expect(lines[1]).toContain('11111111-aaaa-4bbb-8ccc-111111111111');
  });

  /** A spreadsheet is exactly where a divided-by-100 float becomes a reconciliation dispute. */
  it('exports amounts in minor units, untouched', () => {
    expect(paymentsToCsv([payment]).split('\r\n')[1]).toContain('4200');
    expect(paymentsToCsv([payment])).not.toContain('42.00');
  });

  it('quotes a value containing a comma or a quote', () => {
    const csv = paymentsToCsv([{ ...payment, description: 'Order 7, "rush"' }]);
    expect(csv).toContain('"Order 7, ""rush"""');
  });

  /**
   * CSV injection. Metadata is arbitrary merchant-supplied text, so a value beginning `=` is a
   * formula Excel executes on open — quoting alone does not stop it, the prefix does.
   */
  it.each(['=1+1', '+1', '-1', '@SUM(A1)'])('neutralises the formula %s', (value) => {
    const csv = paymentsToCsv([{ ...payment, description: value }]);
    expect(csv).toContain(`"'${value}"`);
  });

  it('serialises metadata rather than dropping it', () => {
    const csv = paymentsToCsv([{ ...payment, metadata: { order_id: 'ord_7' } }]);
    expect(csv).toContain('order_id');
  });

  it('writes a header for an empty export rather than an empty file', () => {
    expect(paymentsToCsv([])).toBe(
      'id,status,amount_minor,captured_amount_minor,refunded_amount_minor,currency,mode,' +
        'created_at,description,failure_reason,payment_method_token,metadata',
    );
  });

  /** Test and live are the same columns with entirely different meaning. */
  it('names the mode in the filename', () => {
    const name = csvFilename('live', new Date('2026-08-09T10:00:00Z'));
    expect(name).toContain('live');
    expect(name).not.toContain(':');
    expect(name.endsWith('.csv')).toBe(true);
  });
});
