'use client';

import { Activity, AlertCircle, RotateCw } from 'lucide-react';
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
import { FilterBar, type FilterOption } from '@/components/patterns/filter-bar';
import { StatusPill } from '@/components/patterns/status-pill';
import { Button } from '@/components/ui/button';
import { CopyButton } from '@/components/ui/copy-button';
import { Drawer } from '@/components/ui/drawer';
import { JsonViewer } from '@/components/ui/json-viewer';
import { Skeleton } from '@/components/ui/skeleton';
import type { EventResponse } from '@/generated/models';
import { formatDateTime, truncateId } from '@/lib/format';
import { PlatformRequestError } from '@/lib/query/platform';
import { usePlatformList } from '@/lib/query/use-platform';

const COLUMNS = ['Event', 'Type', 'Related payment', 'Created'] as const;

const EVENT_TYPES = [
  'payment.created',
  'payment.authorized',
  'payment.captured',
  'payment.failed',
  'payment.refunded',
  'payment.partially_refunded',
  'payment.voided',
] as const;

const TYPE_FILTERS: readonly FilterOption[] = EVENT_TYPES.map((t) => ({
  id: `type:${t}`,
  label: t,
  group: 'Type',
}));

export function EventsBrowser() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const typeFilter = searchParams.get('type') ?? undefined;
  const [openEvent, setOpenEvent] = React.useState<EventResponse | null>(null);

  const selected = React.useMemo(() => (typeFilter ? [`type:${typeFilter}`] : []), [typeFilter]);

  const params = React.useMemo(
    () => ({ limit: 25, ...(typeFilter ? { type: typeFilter } : {}) }),
    [typeFilter],
  );
  const query = usePlatformList<EventResponse>('listEvents', params);
  const rows = React.useMemo(
    () => (query.data?.pages ?? []).flatMap((p) => p.data ?? []),
    [query.data],
  );

  const applyType = (next: readonly string[]) => {
    const value = next[0]?.split(':')[1];
    const sp = new URLSearchParams(searchParams.toString());
    if (value) sp.set('type', value);
    else sp.delete('type');
    router.replace(sp.toString() ? `/developers/events?${sp}` : '/developers/events', {
      scroll: false,
    });
  };

  return (
    <div className="flex flex-col gap-4">
      <FilterBar options={TYPE_FILTERS} selected={selected} onChange={applyType} />

      {query.isError ? (
        <QueryError error={query.error} onRetry={() => void query.refetch()} />
      ) : query.isPending ? (
        <DataTable>
          <DataTableHead columns={COLUMNS} />
          <tbody aria-hidden>
            {Array.from({ length: 8 }, (_, i) => (
              <tr key={i} className="border-b border-border-subtle last:border-0">
                {COLUMNS.map((c) => (
                  <td key={c} className="h-9 px-4">
                    <Skeleton className="h-3 w-24" />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </DataTable>
      ) : rows.length === 0 ? (
        <EmptyState
          icon={<Activity className="size-5 text-accent" aria-hidden />}
          title={selected.length ? 'No events match' : 'No events yet'}
          description="Events are emitted the moment a payment state change commits."
        />
      ) : (
        <>
          <DataTable>
            <DataTableHead columns={COLUMNS} />
            <DataTableBody>
              {rows.map((ev) => {
                const data = (ev.data ?? {}) as Record<string, unknown>;
                const paymentId =
                  typeof data.id === 'string'
                    ? data.id
                    : typeof data.paymentId === 'string'
                      ? data.paymentId
                      : undefined;
                return (
                  <DataTableRow
                    key={ev.id ?? Math.random()}
                    role="button"
                    tabIndex={0}
                    className="cursor-pointer"
                    onClick={() => setOpenEvent(ev)}
                    onKeyDown={(e: React.KeyboardEvent) => {
                      if (e.key === 'Enter') setOpenEvent(ev);
                    }}
                  >
                    <DataTableCell className="font-mono text-fg">
                      {ev.id ? truncateId(ev.id) : '—'}
                    </DataTableCell>
                    <DataTableCell>
                      <StatusPill status={ev.type} family="event" dot label={ev.type} />
                    </DataTableCell>
                    <DataTableCell className="font-mono text-label-sm text-fg-subtle">
                      {paymentId ? truncateId(paymentId) : '—'}
                    </DataTableCell>
                    <DataTableCell className="whitespace-nowrap">
                      {ev.created ? formatDateTime(ev.created) : '—'}
                    </DataTableCell>
                  </DataTableRow>
                );
              })}
            </DataTableBody>
          </DataTable>

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

      <Drawer
        open={openEvent !== null}
        onOpenChange={(o) => !o && setOpenEvent(null)}
        title={openEvent?.type ?? 'Event'}
        description={openEvent?.id}
        footer={
          openEvent?.id ? (
            <CopyButton value={openEvent.id} label="Copy event id" variant="secondary" size="sm" />
          ) : null
        }
      >
        {openEvent ? (
          <div className="space-y-3">
            <div className="flex flex-wrap gap-x-6 gap-y-1 text-label-sm">
              <span className="text-fg-subtle">
                Created{' '}
                <span className="text-fg-muted">
                  {openEvent.created ? formatDateTime(openEvent.created) : '—'}
                </span>
              </span>
              <span className="text-fg-subtle">
                Mode <span className="text-fg-muted">{openEvent.mode ?? '—'}</span>
              </span>
            </div>
            <JsonViewer data={openEvent.data ?? {}} />
          </div>
        ) : null}
      </Drawer>
    </div>
  );
}

function QueryError({ error, onRetry }: { error: Error; onRetry: () => void }) {
  const platform = error instanceof PlatformRequestError ? error : undefined;
  return (
    <div className="flex items-start gap-3 rounded-lg bg-surface p-5 ring-hairline">
      <AlertCircle className="mt-0.5 size-4 shrink-0 text-danger" aria-hidden />
      <div className="min-w-0 flex-1">
        <p className="text-label font-[510] text-fg">Could not load events</p>
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
