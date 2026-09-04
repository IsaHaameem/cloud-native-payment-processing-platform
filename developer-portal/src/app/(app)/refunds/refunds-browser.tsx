'use client';

import { AlertCircle, Receipt, RotateCw } from 'lucide-react';
import { useRouter, useSearchParams } from 'next/navigation';
import * as React from 'react';

import { EmptyState } from '@/components/patterns/empty-state';
import { FilterBar, type FilterOption } from '@/components/patterns/filter-bar';
import { StatusPill } from '@/components/patterns/status-pill';
import {
  DataTable,
  DataTableBody,
  DataTableCell,
  DataTableHead,
  DataTableRow,
} from '@/components/patterns/data-table';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import type { RefundResponse } from '@/generated/models';
import { formatDateTime, formatMoney, truncateId } from '@/lib/format';
import { PlatformRequestError } from '@/lib/query/platform';
import { usePlatformList } from '@/lib/query/use-platform';

const COLUMNS = ['Refund', 'Payment', 'Status', 'Amount', 'Reason', 'Created'] as const;
const PAGE_SIZE = 25;

const STATUS_FILTERS: readonly FilterOption[] = [
  { id: 'status:succeeded', label: 'Succeeded', group: 'Status' },
  { id: 'status:pending', label: 'Pending', group: 'Status' },
  { id: 'status:failed', label: 'Failed', group: 'Status' },
];

export function RefundsBrowser() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const paymentFilter = searchParams.get('payment') ?? undefined;
  const statusFilter = searchParams.get('status') ?? undefined;

  const selected = React.useMemo(
    () => (statusFilter ? [`status:${statusFilter}`] : []),
    [statusFilter],
  );

  const params = React.useMemo(
    () => ({
      limit: PAGE_SIZE,
      ...(statusFilter ? { status: statusFilter } : {}),
      ...(paymentFilter ? { payment: paymentFilter } : {}),
    }),
    [statusFilter, paymentFilter],
  );

  const query = usePlatformList<RefundResponse>('listRefunds', params);

  const rows = React.useMemo(
    () => (query.data?.pages ?? []).flatMap((page) => page.data ?? []),
    [query.data],
  );

  const applyStatus = (next: readonly string[]) => {
    const value = next[0]?.split(':')[1];
    const sp = new URLSearchParams(searchParams.toString());
    if (value) sp.set('status', value);
    else sp.delete('status');
    router.replace(sp.toString() ? `/refunds?${sp.toString()}` : '/refunds', { scroll: false });
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <FilterBar options={STATUS_FILTERS} selected={selected} onChange={applyStatus} />
        {paymentFilter ? (
          <Button variant="ghost" size="sm" onClick={() => router.replace('/refunds')}>
            Clear payment filter
          </Button>
        ) : null}
      </div>

      {query.isError ? (
        <QueryError error={query.error} onRetry={() => void query.refetch()} />
      ) : query.isPending ? (
        <Frame>
          <SkeletonRows />
        </Frame>
      ) : rows.length === 0 ? (
        <EmptyState
          icon={<Receipt className="size-5 text-accent" aria-hidden />}
          title={selected.length || paymentFilter ? 'No refunds match' : 'No refunds yet'}
          description={
            selected.length || paymentFilter
              ? 'Nothing on your account matches these filters.'
              : 'Refunds issued from a payment appear here, newest first.'
          }
        />
      ) : (
        <>
          <Frame>
            <DataTableBody>
              {rows.map((r) => (
                <RefundRow key={r.id ?? Math.random()} refund={r} />
              ))}
            </DataTableBody>
          </Frame>
          {query.hasNextPage ? (
            <div className="flex justify-center py-1">
              <Button
                variant="secondary"
                size="sm"
                disabled={query.isFetchingNextPage}
                onClick={() => void query.fetchNextPage()}
              >
                {query.isFetchingNextPage ? (
                  <>
                    <RotateCw className="animate-spin" /> Loading…
                  </>
                ) : (
                  'Load more'
                )}
              </Button>
            </div>
          ) : (
            <p className="py-1 text-center text-label-sm text-fg-muted">End of results.</p>
          )}
        </>
      )}
    </div>
  );
}

function Frame({ children }: { children: React.ReactNode }) {
  return (
    <DataTable>
      <DataTableHead columns={COLUMNS} />
      {children}
    </DataTable>
  );
}

function RefundRow({ refund: r }: { refund: RefundResponse }) {
  const router = useRouter();
  const href = r.id ? `/refunds/${encodeURIComponent(r.id)}` : undefined;
  return (
    <DataTableRow
      {...(href
        ? {
            role: 'link' as const,
            tabIndex: 0,
            className: 'cursor-pointer',
            onClick: () => router.push(href),
            onKeyDown: (e: React.KeyboardEvent) => {
              if (e.key === 'Enter') router.push(href);
            },
          }
        : {})}
    >
      <DataTableCell className="font-mono text-fg">{r.id ? truncateId(r.id) : '—'}</DataTableCell>
      <DataTableCell className="font-mono text-label-sm text-fg-subtle">
        {r.paymentId ? truncateId(r.paymentId) : '—'}
      </DataTableCell>
      <DataTableCell>
        <StatusPill status={r.status} family="refund" dot />
      </DataTableCell>
      <DataTableCell className="tabular text-right text-fg">
        {r.amountMinor !== undefined ? formatMoney(r.amountMinor, r.currency ?? 'USD') : '—'}
      </DataTableCell>
      <DataTableCell className="max-w-[16rem] truncate text-fg-subtle">
        {r.reason ?? '—'}
      </DataTableCell>
      <DataTableCell className="whitespace-nowrap">
        {r.createdAt ? formatDateTime(r.createdAt) : '—'}
      </DataTableCell>
    </DataTableRow>
  );
}

function SkeletonRows() {
  return (
    <tbody aria-hidden>
      {Array.from({ length: 8 }, (_, i) => (
        <tr key={i} className="border-b border-border-subtle last:border-0">
          {COLUMNS.map((c) => (
            <td key={c} className="h-9 px-4">
              <Skeleton className="h-3 w-20" />
            </td>
          ))}
        </tr>
      ))}
    </tbody>
  );
}

function QueryError({ error, onRetry }: { error: Error; onRetry: () => void }) {
  const platform = error instanceof PlatformRequestError ? error : undefined;
  return (
    <div className="flex items-start gap-3 rounded-lg bg-surface p-5 ring-hairline">
      <AlertCircle className="mt-0.5 size-4 shrink-0 text-danger" aria-hidden />
      <div className="min-w-0 flex-1">
        <p className="text-label font-[510] text-fg">Could not load refunds</p>
        <p className="mt-1 text-label text-fg-subtle">{error.message}</p>
        {platform?.requestId ? (
          <p className="mt-2 font-mono text-label-sm text-fg-subtle select-all">
            {platform.requestId}
          </p>
        ) : null}
        <Button variant="secondary" size="sm" className="mt-4" onClick={onRetry}>
          <RotateCw /> Try again
        </Button>
      </div>
    </div>
  );
}
