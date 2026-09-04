'use client';

import { AlertCircle, RotateCw, ScrollText } from 'lucide-react';
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
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { CopyButton } from '@/components/ui/copy-button';
import { Drawer } from '@/components/ui/drawer';
import { JsonViewer } from '@/components/ui/json-viewer';
import { Skeleton } from '@/components/ui/skeleton';
import type { RequestLogResponse } from '@/generated/models';
import { formatDateTime } from '@/lib/format';
import { PlatformRequestError } from '@/lib/query/platform';
import { usePlatformList } from '@/lib/query/use-platform';

const COLUMNS = ['Method', 'Path', 'Status', 'Duration', 'When'] as const;

const METHOD_FILTERS: readonly FilterOption[] = ['GET', 'POST', 'PATCH', 'DELETE'].map((m) => ({
  id: `method:${m}`,
  label: m,
  group: 'Method',
}));

function statusTone(code: number | undefined): 'success' | 'warning' | 'danger' | 'neutral' {
  if (code === undefined) return 'neutral';
  if (code >= 500) return 'danger';
  if (code >= 400) return 'warning';
  if (code >= 200 && code < 300) return 'success';
  return 'neutral';
}

export function LogsBrowser() {
  const [method, setMethod] = React.useState<string | undefined>(undefined);
  const [open, setOpen] = React.useState<RequestLogResponse | null>(null);

  const params = React.useMemo(() => ({ limit: 25, ...(method ? { method } : {}) }), [method]);
  const query = usePlatformList<RequestLogResponse>('listRequestLogs', params);
  const rows = React.useMemo(
    () => (query.data?.pages ?? []).flatMap((p) => p.data ?? []),
    [query.data],
  );

  return (
    <div className="flex flex-col gap-4">
      <FilterBar
        options={METHOD_FILTERS}
        selected={method ? [`method:${method}`] : []}
        onChange={(next) => setMethod(next[0]?.split(':')[1])}
      />

      {query.isError ? (
        <Err error={query.error} onRetry={() => void query.refetch()} />
      ) : query.isPending ? (
        <DataTable>
          <DataTableHead columns={COLUMNS} />
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
        </DataTable>
      ) : rows.length === 0 ? (
        <EmptyState
          icon={<ScrollText className="size-5 text-accent" aria-hidden />}
          title="No requests logged"
          description="Calls made with your API keys appear here."
        />
      ) : (
        <>
          <DataTable>
            <DataTableHead columns={COLUMNS} />
            <DataTableBody>
              {rows.map((r) => (
                <DataTableRow
                  key={r.id ?? Math.random()}
                  role="button"
                  tabIndex={0}
                  className="cursor-pointer"
                  onClick={() => setOpen(r)}
                  onKeyDown={(e: React.KeyboardEvent) => {
                    if (e.key === 'Enter') setOpen(r);
                  }}
                >
                  <DataTableCell className="font-mono text-label-sm text-fg">
                    {r.method ?? '—'}
                  </DataTableCell>
                  <DataTableCell className="max-w-[22rem] truncate font-mono text-label-sm text-fg-muted">
                    {r.path ?? '—'}
                  </DataTableCell>
                  <DataTableCell>
                    <Badge tone={statusTone(r.statusCode)}>
                      <span className="tabular font-mono">{r.statusCode ?? '—'}</span>
                    </Badge>
                  </DataTableCell>
                  <DataTableCell className="tabular text-right text-label-sm">
                    {r.durationMs !== undefined ? `${r.durationMs} ms` : '—'}
                  </DataTableCell>
                  <DataTableCell className="whitespace-nowrap">
                    {r.occurredAt ? formatDateTime(r.occurredAt) : '—'}
                  </DataTableCell>
                </DataTableRow>
              ))}
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
        open={open !== null}
        onOpenChange={(o) => !o && setOpen(null)}
        title={open ? `${open.method ?? ''} ${open.path ?? ''}` : 'Request'}
        description={open?.requestId}
        footer={
          open?.requestId ? (
            <CopyButton
              value={open.requestId}
              label="Copy request id"
              variant="secondary"
              size="sm"
            />
          ) : null
        }
      >
        {open ? (
          <div className="space-y-4">
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-label-sm">
              {(
                [
                  ['Status', String(open.statusCode ?? '—')],
                  ['Duration', open.durationMs !== undefined ? `${open.durationMs} ms` : '—'],
                  ['Key', open.keyId ?? '—'],
                  ['Correlation id', open.correlationId ?? '—'],
                  ['Client IP', open.clientIp ?? '—'],
                  ['Error code', open.errorCode ?? '—'],
                ] as const
              ).map(([k, v]) => (
                <div key={k}>
                  <dt className="text-fg-subtle">{k}</dt>
                  <dd className="font-mono break-all text-fg-muted">{v}</dd>
                </div>
              ))}
            </dl>
            {open.responseBody ? (
              <div>
                <p className="mb-1.5 text-label-sm text-fg-subtle">Response body</p>
                <JsonViewer data={safeParse(open.responseBody)} />
              </div>
            ) : null}
          </div>
        ) : null}
      </Drawer>
    </div>
  );
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function Err({ error, onRetry }: { error: Error; onRetry: () => void }) {
  const platform = error instanceof PlatformRequestError ? error : undefined;
  return (
    <div className="flex items-start gap-3 rounded-lg bg-surface p-5 ring-hairline">
      <AlertCircle className="mt-0.5 size-4 shrink-0 text-danger" aria-hidden />
      <div className="min-w-0 flex-1">
        <p className="text-label font-[510] text-fg">Could not load request logs</p>
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
