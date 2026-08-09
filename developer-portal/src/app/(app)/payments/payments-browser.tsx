'use client';

import { AnimatePresence } from 'framer-motion';
import { AlertCircle, CreditCard, Download, Link2, RotateCw, X } from 'lucide-react';
import { useRouter, useSearchParams } from 'next/navigation';
import * as React from 'react';

import {
  DataTable,
  DataTableBody,
  DataTableCell,
  DataTableHead,
  DataTableRow,
} from '@/components/patterns/data-table';
import { EmptyState } from '@/components/patterns/empty-state';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import type { PaymentResponse } from '@/generated/models';
import { formatDateTime, formatMoney, truncateId } from '@/lib/format';
import { csvFilename, paymentsToCsv } from '@/lib/payments/csv';
import {
  type PaymentFilters,
  activeFilterCount,
  filtersFromSearchParams,
  hasClientSideNarrowing,
  isFiltered,
  matchesClientSide,
  searchParamsFromFilters,
  toPlatformParams,
} from '@/lib/payments/filters';
import { PlatformRequestError } from '@/lib/query/platform';
import { useQueryScope } from '@/lib/query/scope';
import { usePlatformList } from '@/lib/query/use-platform';

import { PaymentFilterBar } from './payment-filters';
import { StatusBadge } from './status-badge';

/**
 * The payments list (M23.6).
 *
 * ── Nothing on this screen is optimistic, and nothing is computed ─────────────────────
 *
 * Every figure is a field the platform returned, rendered as it arrived. No amount is summed, no
 * status is inferred from another field, and no row changes because the client thinks something
 * happened. M23.6 issues no mutations at all — capture, refund and void are M23.7's — so the
 * strongest form of "never show unconfirmed financial state" is available here: there is no state
 * to confirm.
 *
 * ── Geometry is preserved while loading ───────────────────────────────────────────────
 *
 * A filter change re-queries, and the table keeps its height and column widths while it does:
 * skeleton rows replace data rows, and the header, the toolbar and the row count stay put. A
 * spinner over a collapsed table makes the page jump twice per keystroke, which is how a fast
 * screen comes to feel slow.
 *
 * The mount stagger `DataTableBody` applies is deliberately *not* re-keyed on a filter change —
 * `data-table.tsx` warns about exactly that — so re-filtering fades rows rather than replaying an
 * animation the user has already watched.
 */

/** One page. The API clamps to 100; 25 is its default and the size that fills a screen once. */
const PAGE_SIZE = 25;

const COLUMNS = ['Payment', 'Status', 'Amount', 'Method', 'Metadata', 'Created'] as const;

export function PaymentsBrowser() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const scope = useQueryScope();

  const filters = React.useMemo(
    () => filtersFromSearchParams(new URLSearchParams(searchParams.toString())),
    [searchParams],
  );

  /*
   * The query's parameters are derived from the URL, so the URL is the single source of truth for
   * what is on screen. Changing a filter is a navigation, which makes the back button work, makes
   * the view shareable, and means there is no second copy of the filter state to fall out of step
   * with the address bar.
   */
  const params = React.useMemo(() => toPlatformParams(filters, PAGE_SIZE), [filters]);

  const query = usePlatformList<PaymentResponse>('listPayments', params);

  const applyFilters = React.useCallback(
    (next: PaymentFilters) => {
      const search = searchParamsFromFilters(next).toString();
      router.replace(search ? `/payments?${search}` : '/payments', { scroll: false });
    },
    [router],
  );

  const loaded = React.useMemo(
    () => (query.data?.pages ?? []).flatMap((page) => page.data ?? []),
    [query.data],
  );

  // The one narrowing the server could not express. It filters *within* pages the platform already
  // scoped to this merchant and mode, so it can only ever remove rows, never introduce one.
  const rows = React.useMemo(
    () => loaded.filter((payment) => matchesClientSide(filters, payment)),
    [loaded, filters],
  );

  const filtered = isFiltered(filters);
  const initialLoad = query.isPending;
  const refiltering = query.isFetching && !query.isFetchingNextPage && !initialLoad;

  return (
    <div className="flex flex-col gap-4">
      <PaymentFilterBar
        filters={filters}
        onChange={applyFilters}
        count={activeFilterCount(filters)}
      />

      <Toolbar
        rows={rows}
        mode={scope.mode}
        loading={initialLoad}
        refiltering={refiltering}
        hasMore={query.hasNextPage === true}
      />

      {hasClientSideNarrowing(filters) ? (
        <p className="flex items-start gap-2 rounded-md bg-surface-inset p-3 text-label-sm text-fg-subtle ring-hairline">
          <AlertCircle aria-hidden className="mt-0.5 size-3.5 shrink-0 text-info" />
          {/*
            Said plainly rather than hidden. The API takes one status, so the extra selections are
            applied to the rows that arrived — which means a page can show fewer rows than it
            fetched, and "load more" advances the platform's cursor rather than this list's length.
          */}
          <span>
            The API filters on one status at a time. The first is applied by the platform; the rest
            are applied to the rows already loaded, so a page may show fewer rows than it fetched.
          </span>
        </p>
      ) : null}

      {query.isError ? (
        <QueryError error={query.error} onRetry={() => void query.refetch()} />
      ) : initialLoad ? (
        <TableFrame>
          <SkeletonRows count={8} />
        </TableFrame>
      ) : rows.length === 0 ? (
        <EmptyState
          icon={<CreditCard aria-hidden className="size-5 text-accent" />}
          title={filtered ? 'No payments match these filters' : `No ${scope.mode} payments yet`}
          description={
            filtered
              ? 'Nothing on your account matches. Clear the filters to see everything, or widen one of them.'
              : 'Payments created with your API keys appear here, newest first.'
          }
          action={
            filtered ? (
              <Button variant="secondary" onClick={() => router.replace('/payments')}>
                <X />
                Clear filters
              </Button>
            ) : undefined
          }
        />
      ) : (
        <>
          {/*
            The dim lives on the container, not on the rows.
            
            `DataTableBody` owns the mount stagger, and it is deliberately *not* re-keyed when the
            filters change — `data-table.tsx` warns that re-animating a filtered re-render is how
            motion turns into latency. So the whole table fades slightly while a new page is in
            flight and the rows underneath simply swap, which keeps the geometry fixed and replays
            nothing.
          */}
          <div
            data-refiltering={refiltering || undefined}
            className="transition-opacity duration-(--duration-fast) data-refiltering:opacity-55"
          >
            <TableFrame>
              <DataTableBody>
                <AnimatePresence initial={false}>
                  {rows.map((payment) => (
                    <PaymentRow key={payment.id ?? Math.random()} payment={payment} />
                  ))}
                </AnimatePresence>
              </DataTableBody>
            </TableFrame>
          </div>

          <LoadMore query={query} shown={rows.length} />
        </>
      )}
    </div>
  );
}

/* ── Chrome ────────────────────────────────────────────────────────────────────────── */

function TableFrame({ children }: { children: React.ReactNode }) {
  return (
    <DataTable>
      <DataTableHead columns={COLUMNS} />
      {children}
    </DataTable>
  );
}

function Toolbar({
  rows,
  mode,
  loading,
  refiltering,
  hasMore,
}: {
  rows: readonly PaymentResponse[];
  mode: string;
  loading: boolean;
  refiltering: boolean;
  hasMore: boolean;
}) {
  const [copied, setCopied] = React.useState(false);

  React.useEffect(() => {
    if (!copied) return;
    const timer = setTimeout(() => setCopied(false), 2000);
    return () => clearTimeout(timer);
  }, [copied]);

  function exportCsv() {
    const blob = new Blob([paymentsToCsv(rows)], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = csvFilename(mode);
    anchor.click();
    // Revoked immediately: the object URL is a handle to payment data held in the tab, and it
    // stays valid for the document's lifetime unless it is released.
    URL.revokeObjectURL(url);
  }

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(window.location.href);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <p aria-live="polite" className="text-label text-fg-subtle">
        {loading ? (
          <Skeleton className="inline-block h-3.5 w-28 align-middle" />
        ) : (
          <>
            <span className="tabular text-fg">{rows.length}</span>
            {hasMore ? '+' : ''} payment{rows.length === 1 && !hasMore ? '' : 's'}
            {refiltering ? ' · updating…' : ''}
          </>
        )}
      </p>

      <div className="flex flex-wrap items-center gap-2">
        <Button variant="secondary" size="sm" onClick={() => void copyLink()}>
          <Link2 />
          {copied ? 'Link copied' : 'Copy view link'}
        </Button>
        {/*
          The count is on the button because the file contains the rows that are loaded, not every
          row that matches — there is no export endpoint in the contract, and paging silently to
          the end of a merchant's history behind one click is worse than saying what you get.
        */}
        <Button variant="secondary" size="sm" disabled={rows.length === 0} onClick={exportCsv}>
          <Download />
          Export {rows.length} row{rows.length === 1 ? '' : 's'}
        </Button>
      </div>
    </div>
  );
}

function LoadMore({
  query,
  shown,
}: {
  query: ReturnType<typeof usePlatformList<PaymentResponse>>;
  shown: number;
}) {
  if (query.hasNextPage !== true) {
    return (
      <p className="py-1 text-center text-label-sm text-fg-muted">
        {shown > 0 ? 'End of results.' : null}
      </p>
    );
  }

  return (
    <div className="flex justify-center py-1">
      <Button
        variant="secondary"
        size="sm"
        disabled={query.isFetchingNextPage}
        onClick={() => void query.fetchNextPage()}
      >
        {query.isFetchingNextPage ? (
          <>
            <RotateCw className="animate-spin" />
            Loading…
          </>
        ) : (
          'Load more'
        )}
      </Button>
    </div>
  );
}

/**
 * What a failed read says.
 *
 * §6.6 requires the request id on every surfaced error, and it is the whole reason this component
 * exists rather than a line of red text: an id is what turns "it broke" into a support request
 * somebody can answer. The platform's own code is shown next to it for the same reason.
 */
function QueryError({ error, onRetry }: { error: Error; onRetry: () => void }) {
  const platform = error instanceof PlatformRequestError ? error : undefined;

  return (
    <div className="flex items-start gap-3 rounded-lg bg-surface p-5 ring-hairline">
      <AlertCircle aria-hidden className="mt-0.5 size-4 shrink-0 text-danger" />
      <div className="min-w-0 flex-1">
        <p className="text-label font-[510] text-fg">Could not load payments</p>
        <p className="mt-1 text-label text-fg-subtle">{error.message}</p>

        {platform ? (
          <dl className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-label-sm">
            {platform.code ? (
              <div className="flex gap-1.5">
                <dt className="text-fg-muted">Code</dt>
                <dd className="font-mono text-fg-subtle">{platform.code}</dd>
              </div>
            ) : null}
            {platform.requestId ? (
              <div className="flex gap-1.5">
                <dt className="text-fg-muted">Request</dt>
                <dd className="font-mono text-fg-subtle select-all">{platform.requestId}</dd>
              </div>
            ) : null}
          </dl>
        ) : null}

        <Button variant="secondary" size="sm" className="mt-4" onClick={onRetry}>
          <RotateCw />
          Try again
        </Button>
      </div>
    </div>
  );
}

/* ── Rows ──────────────────────────────────────────────────────────────────────────── */

function PaymentRow({ payment }: { payment: PaymentResponse }) {
  return (
    <DataTableRow
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.12 }}
    >
      {/*
        The full id is on the cell, not just its tail.

        `truncateId` keeps a prefix and six characters, which is right for `evt_…` but leaves a
        payment — a bare UUID, per D208 — showing only its last six. That is enough to tell two
        rows apart and not enough to paste into a support request, so the whole value is available
        on hover and to assistive technology, and `select-all` makes one click take it.
      */}
      <DataTableCell className="font-mono text-fg">
        {payment.id ? (
          <span title={payment.id} className="select-all">
            {truncateId(payment.id)}
          </span>
        ) : (
          '—'
        )}
      </DataTableCell>

      <DataTableCell>
        <StatusBadge status={payment.status} />
      </DataTableCell>

      {/*
        Right-aligned and tabular so the decimal points line up down the column — the one thing a
        money column has to do that a text column does not. The value is the platform's minor-unit
        integer formatted for display; nothing is added up here.
      */}
      <DataTableCell className="text-right tabular text-fg">
        {payment.amountMinor !== undefined && payment.currency
          ? formatMoney(payment.amountMinor, payment.currency)
          : '—'}
        {payment.refundedAmountMinor !== undefined && payment.refundedAmountMinor > 0 ? (
          <span className="ml-1.5 text-label-sm text-fg-muted">
            −
            {payment.currency
              ? formatMoney(payment.refundedAmountMinor, payment.currency)
              : payment.refundedAmountMinor}
          </span>
        ) : null}
      </DataTableCell>

      <DataTableCell className="font-mono text-label-sm">
        {payment.paymentMethodToken ? truncateId(payment.paymentMethodToken, 4) : '—'}
      </DataTableCell>

      <DataTableCell className="max-w-[16rem]">
        <MetadataPreview metadata={payment.metadata} />
      </DataTableCell>

      <DataTableCell className="whitespace-nowrap">
        {payment.createdAt ? formatDateTime(payment.createdAt) : '—'}
      </DataTableCell>
    </DataTableRow>
  );
}

/** The first two pairs, with a count for the rest. A preview, per §6.2 — not the whole map. */
function MetadataPreview({ metadata }: { metadata: Record<string, string> | undefined }) {
  const entries = Object.entries(metadata ?? {});
  if (entries.length === 0) return <span className="text-fg-faint">—</span>;

  const shown = entries.slice(0, 2);

  return (
    <span className="flex flex-wrap items-center gap-1">
      {shown.map(([key, value]) => (
        <Badge key={key} tone="outline" className="max-w-full">
          <span className="truncate font-mono text-caption">
            {key}={value}
          </span>
        </Badge>
      ))}
      {entries.length > shown.length ? (
        <span className="text-label-sm text-fg-muted">+{entries.length - shown.length}</span>
      ) : null}
    </span>
  );
}

/**
 * The loading state.
 *
 * Skeletons rather than a spinner, and in the table rather than over it: the row height, the
 * column widths and the page height are all the same as they will be with data, so nothing moves
 * when the rows arrive.
 */
function SkeletonRows({ count }: { count: number }) {
  return (
    <tbody aria-hidden data-testid="payments-skeleton">
      {Array.from({ length: count }, (_, index) => (
        <tr key={index} className="border-b border-border-subtle last:border-0">
          <td className="h-9 px-4">
            <Skeleton className="h-3 w-24" />
          </td>
          <td className="h-9 px-4">
            <Skeleton className="h-3.5 w-16 rounded-full" />
          </td>
          <td className="h-9 px-4">
            <Skeleton className="ml-auto h-3 w-16" />
          </td>
          <td className="h-9 px-4">
            <Skeleton className="h-3 w-12" />
          </td>
          <td className="h-9 px-4">
            <Skeleton className="h-3 w-28" />
          </td>
          <td className="h-9 px-4">
            <Skeleton className="h-3 w-32" />
          </td>
        </tr>
      ))}
    </tbody>
  );
}
