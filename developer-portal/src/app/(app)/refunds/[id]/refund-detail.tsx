'use client';

import { ArrowLeft } from 'lucide-react';
import Link from 'next/link';

import { DetailList } from '@/components/patterns/detail-list';
import { ErrorState } from '@/components/patterns/error-state';
import { StatusPill } from '@/components/patterns/status-pill';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { CopyField } from '@/components/ui/copy-button';
import { Skeleton } from '@/components/ui/skeleton';
import type { RefundResponse } from '@/generated/models';
import { formatDateTime, formatMoney, truncateId } from '@/lib/format';
import { PlatformRequestError } from '@/lib/query/platform';
import { usePlatformObject } from '@/lib/query/use-platform';

export function RefundDetail({ id }: { id: string }) {
  const query = usePlatformObject<RefundResponse>('getRefund', id);

  if (query.isError) {
    const e = query.error;
    const notFound = e instanceof PlatformRequestError && e.status === 404;
    return (
      <div className="py-8">
        <h1 tabIndex={-1} className="sr-only">
          {notFound ? 'Refund not found' : 'Could not load refund'}
        </h1>
        <Back />
        <ErrorState
          title={notFound ? 'Refund not found' : 'Could not load this refund'}
          description={
            notFound ? 'No refund with this id belongs to your account in this mode.' : e.message
          }
          {...(e instanceof PlatformRequestError && e.requestId ? { requestId: e.requestId } : {})}
          onRetry={notFound ? undefined : () => void query.refetch()}
        />
      </div>
    );
  }

  if (query.isPending || !query.data) {
    return (
      <div className="pb-10">
        <h1 tabIndex={-1} className="sr-only">
          Loading refund
        </h1>
        <Back />
        <Skeleton className="mt-4 h-9 w-40" />
        <Skeleton className="mt-6 h-48 w-full rounded-lg" />
      </div>
    );
  }

  const r = query.data;
  const currency = r.currency ?? 'USD';

  return (
    <div className="pb-10">
      <Back />
      <div className="mt-3 flex flex-wrap items-center gap-2.5">
        <h1
          tabIndex={-1}
          className="tabular text-title-1 leading-none font-[510] tracking-[-0.88px] text-fg outline-none"
        >
          {r.amountMinor !== undefined ? formatMoney(r.amountMinor, currency) : '—'}
          <span className="sr-only"> refund</span>
        </h1>
        <span className="text-label text-fg-subtle">{currency}</span>
        <StatusPill status={r.status} family="refund" dot />
        {r.mode ? <Badge tone={r.mode === 'TEST' ? 'test' : 'outline'}>{r.mode}</Badge> : null}
      </div>
      {r.id ? (
        <div className="mt-2">
          <CopyField value={r.id} display={truncateId(r.id)} />
        </div>
      ) : null}

      <div className="mt-6 grid gap-4 grid-cols-1 lg:grid-cols-2">
        <Card>
          <CardContent className="pt-4">
            <h2 className="mb-3 text-label font-[510] text-fg">Refund</h2>
            <DetailList
              rows={[
                { label: 'Reason', value: r.reason ?? '—' },
                {
                  label: 'Payment',
                  value: r.paymentId ? (
                    <Link
                      href={`/payments/${encodeURIComponent(r.paymentId)}`}
                      className="text-accent-text hover:underline"
                    >
                      {truncateId(r.paymentId)}
                    </Link>
                  ) : (
                    '—'
                  ),
                },
                ...(r.status?.toLowerCase() === 'failed'
                  ? [{ label: 'Failure reason', value: r.failureReason ?? 'unknown', mono: true }]
                  : []),
              ]}
            />
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-4">
            <h2 className="mb-3 text-label font-[510] text-fg">Technical</h2>
            <DetailList
              rows={[
                { label: 'ID', value: r.id ?? '—', mono: true },
                { label: 'Merchant', value: r.merchantId ?? '—', mono: true },
                { label: 'Mode', value: r.mode ?? '—', mono: true },
                { label: 'Created', value: r.createdAt ? formatDateTime(r.createdAt) : '—' },
                { label: 'Updated', value: r.updatedAt ? formatDateTime(r.updatedAt) : '—' },
              ]}
            />
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function Back() {
  return (
    <Link
      href="/refunds"
      className="inline-flex items-center gap-1.5 text-label text-fg-subtle hover:text-fg"
    >
      <ArrowLeft className="size-3.5" /> Refunds
    </Link>
  );
}
