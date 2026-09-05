'use client';

import { AlertCircle, RotateCw, Wallet } from 'lucide-react';
import Link from 'next/link';
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
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import type { BalanceResponse, BalanceTransactionResponse } from '@/generated/models';
import { formatDateTime, formatMoney, truncateId } from '@/lib/format';
import { PlatformRequestError } from '@/lib/query/platform';
import { usePlatformList, usePlatformQuery } from '@/lib/query/use-platform';

const COLUMNS = ['Date', 'Type', 'Payment', 'Account', 'Direction', 'Amount'] as const;

const ACCOUNT_LABEL: Record<string, string> = {
  MERCHANT_PENDING: 'Pending',
  MERCHANT_SETTLED: 'Settled',
};

export function BalanceView() {
  const balance = usePlatformQuery<BalanceResponse>('getBalance');
  const txns = usePlatformList<BalanceTransactionResponse>('listBalanceTransactions', {
    limit: 25,
  });

  const rows = React.useMemo(
    () => (txns.data?.pages ?? []).flatMap((p) => p.data ?? []),
    [txns.data],
  );

  return (
    <div className="flex flex-col gap-6">
      {/* Balance cards */}
      {balance.isPending ? (
        <div className="grid gap-4 grid-cols-1 sm:grid-cols-2 lg:grid-cols-3">
          {[0, 1].map((i) => (
            <Skeleton key={i} className="h-24 rounded-lg" />
          ))}
        </div>
      ) : balance.isError ? (
        <CardError error={balance.error} onRetry={() => void balance.refetch()} label="balance" />
      ) : (balance.data?.balances ?? []).length === 0 ? (
        <EmptyState
          icon={<Wallet className="size-5 text-accent" aria-hidden />}
          title="No balance activity yet"
          description="Once a payment is captured, the currency you hold a balance in appears here."
        />
      ) : (
        <div className="grid gap-4 grid-cols-1 sm:grid-cols-2 lg:grid-cols-3">
          {(balance.data?.balances ?? []).map((b) => (
            <Card key={b.currency}>
              <CardContent className="pt-4">
                <div className="flex items-center justify-between">
                  <p className="text-label-sm font-[510] text-fg-subtle">{b.currency}</p>
                </div>
                <p className="tabular mt-2 text-title-1 leading-none font-[510] tracking-[-0.88px] text-fg">
                  {formatMoney(b.availableMinor ?? 0, b.currency ?? 'USD')}
                </p>
                <p className="mt-1 text-label-sm text-fg-subtle">available</p>
                <p className="tabular mt-3 text-label text-fg-muted">
                  {formatMoney(b.pendingMinor ?? 0, b.currency ?? 'USD')}{' '}
                  <span className="text-fg-subtle">pending</span>
                </p>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* Transactions table */}
      <div>
        <h2 className="mb-3 text-title-2 font-[510] tracking-[-0.165px] text-fg">
          Balance transactions
        </h2>
        {txns.isError ? (
          <CardError error={txns.error} onRetry={() => void txns.refetch()} label="transactions" />
        ) : txns.isPending ? (
          <DataTable>
            <DataTableHead columns={COLUMNS} />
            <tbody aria-hidden>
              {Array.from({ length: 6 }, (_, i) => (
                <tr key={i} className="border-b border-border-subtle last:border-0">
                  {COLUMNS.map((c) => (
                    <td key={c} className="h-9 px-4">
                      <Skeleton className="h-3 w-16" />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </DataTable>
        ) : rows.length === 0 ? (
          <EmptyState title="No balance activity yet" />
        ) : (
          <>
            <DataTable>
              <DataTableHead columns={COLUMNS} />
              <DataTableBody>
                {rows.map((t) => {
                  const debit = (t.direction ?? '').toUpperCase() === 'DEBIT';
                  return (
                    <DataTableRow key={t.id ?? Math.random()}>
                      <DataTableCell className="whitespace-nowrap">
                        {t.createdAt ? formatDateTime(t.createdAt) : '—'}
                      </DataTableCell>
                      <DataTableCell className="font-mono text-label-sm">
                        {t.eventType ?? '—'}
                      </DataTableCell>
                      <DataTableCell className="font-mono text-label-sm">
                        {t.paymentId ? (
                          <Link
                            href={`/payments/${encodeURIComponent(t.paymentId)}`}
                            className="text-accent-text hover:underline"
                          >
                            {truncateId(t.paymentId)}
                          </Link>
                        ) : (
                          '—'
                        )}
                      </DataTableCell>
                      <DataTableCell>
                        {t.accountType ? (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <span className="cursor-help">
                                {ACCOUNT_LABEL[t.accountType] ?? t.accountType}
                              </span>
                            </TooltipTrigger>
                            <TooltipContent>{t.accountType}</TooltipContent>
                          </Tooltip>
                        ) : (
                          '—'
                        )}
                      </DataTableCell>
                      <DataTableCell>
                        <Badge tone={debit ? 'danger' : 'success'}>{t.direction ?? '—'}</Badge>
                      </DataTableCell>
                      <DataTableCell
                        className={`tabular text-right ${debit ? 'text-danger' : 'text-success'}`}
                      >
                        {t.amountMinor !== undefined
                          ? `${debit ? '−' : '+'}${formatMoney(t.amountMinor, t.currency ?? 'USD')}`
                          : '—'}
                      </DataTableCell>
                    </DataTableRow>
                  );
                })}
              </DataTableBody>
            </DataTable>
            {txns.hasNextPage ? (
              <div className="mt-3 flex justify-center">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={txns.isFetchingNextPage}
                  onClick={() => void txns.fetchNextPage()}
                >
                  {txns.isFetchingNextPage ? (
                    <>
                      <RotateCw className="animate-spin" /> Loading…
                    </>
                  ) : (
                    'Load more'
                  )}
                </Button>
              </div>
            ) : null}
          </>
        )}
      </div>
    </div>
  );
}

function CardError({
  error,
  onRetry,
  label,
}: {
  error: Error;
  onRetry: () => void;
  label: string;
}) {
  const platform = error instanceof PlatformRequestError ? error : undefined;
  return (
    <div className="flex items-start gap-3 rounded-lg bg-surface p-5 ring-hairline">
      <AlertCircle className="mt-0.5 size-4 shrink-0 text-danger" aria-hidden />
      <div className="min-w-0 flex-1">
        <p className="text-label font-[510] text-fg">Could not load {label}</p>
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
