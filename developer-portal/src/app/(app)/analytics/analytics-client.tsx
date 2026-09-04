'use client';

import * as React from 'react';

import { MetricCard } from '@/components/patterns/metric-card';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs } from '@/components/ui/tabs';
import type { AnalyticsSummaryResponse, UsageSummaryResponse } from '@/generated/models';
import { formatMoney } from '@/lib/format';
import { usePlatformQuery } from '@/lib/query/use-platform';

const RANGES = [
  { id: '24h', label: '24 hours', hours: 24 },
  { id: '7d', label: '7 days', hours: 24 * 7 },
  { id: '30d', label: '30 days', hours: 24 * 30 },
] as const;

export function AnalyticsClient() {
  const [tab, setTab] = React.useState<'payments' | 'usage'>('payments');
  const [rangeId, setRangeId] = React.useState<(typeof RANGES)[number]['id']>('7d');
  const range = RANGES.find((r) => r.id === rangeId) ?? RANGES[1];

  const { from, to } = React.useMemo(() => {
    const now = new Date();
    return {
      from: new Date(now.getTime() - range.hours * 3_600_000).toISOString(),
      to: now.toISOString(),
    };
  }, [range.hours]);

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Tabs
          aria-label="Analytics"
          value={tab}
          onValueChange={(v) => setTab(v as 'payments' | 'usage')}
          items={[
            { id: 'payments', label: 'Payments' },
            { id: 'usage', label: 'API usage' },
          ]}
        />
        <div className="flex items-center gap-1 rounded-md bg-surface-inset p-1 ring-hairline">
          {RANGES.map((r) => (
            <button
              key={r.id}
              type="button"
              onClick={() => setRangeId(r.id)}
              aria-pressed={r.id === rangeId}
              className={`h-7 rounded-sm px-3 text-label-sm font-[510] transition-colors ${
                r.id === rangeId ? 'bg-surface-active text-fg' : 'text-fg-subtle hover:text-fg'
              }`}
            >
              {r.label}
            </button>
          ))}
        </div>
      </div>

      {tab === 'payments' ? <PaymentsTab from={from} to={to} /> : <UsageTab from={from} to={to} />}
    </div>
  );
}

function PaymentsTab({ from, to }: { from: string; to: string }) {
  const q = usePlatformQuery<AnalyticsSummaryResponse>('getPaymentAnalytics', { from, to });
  if (q.isPending) return <GridSkeleton n={6} />;
  if (q.isError) return <ErrLine message={q.error.message} />;
  const a = q.data ?? {};

  const series = buildSeries(a.buckets, 'totalCapturedAmountMinor');

  return (
    <>
      <div className="grid gap-3 grid-cols-1 sm:grid-cols-2 lg:grid-cols-4">
        <MetricCard label="Created" value={a.createdCount ?? 0} format={fmtInt} />
        <MetricCard label="Authorized" value={a.authorizedCount ?? 0} format={fmtInt} />
        <MetricCard label="Captured" value={a.capturedCount ?? 0} format={fmtInt} />
        <MetricCard label="Failed" value={a.failedCount ?? 0} format={fmtInt} invertDelta />
        <MetricCard label="Voided" value={a.voidedCount ?? 0} format={fmtInt} />
        <MetricCard label="Refunded" value={a.refundedCount ?? 0} format={fmtInt} />
        <MetricCard
          label="Success rate"
          value={a.successRate == null ? 0 : a.successRate * 100}
          format={(v) => (a.successRate == null ? '—' : `${v.toFixed(1)}%`)}
        />
        <MetricCard
          label="Captured volume"
          value={a.totalCapturedAmountMinor ?? 0}
          format={(v) => formatMoney(Math.round(v), 'USD')}
        />
      </div>
      {series.length > 1 ? (
        <Card>
          <CardContent className="pt-4">
            <h2 className="mb-3 text-label font-[510] text-fg">Captured volume over time</h2>
            <LineChart series={series} />
          </CardContent>
        </Card>
      ) : null}
    </>
  );
}

function UsageTab({ from, to }: { from: string; to: string }) {
  const q = usePlatformQuery<UsageSummaryResponse>('getUsage', { from, to });
  if (q.isPending) return <GridSkeleton n={3} />;
  if (q.isError) return <ErrLine message={q.error.message} />;
  const u = q.data ?? {};
  return (
    <div className="grid gap-3 grid-cols-1 sm:grid-cols-3">
      <MetricCard label="Total requests" value={u.totalRequests ?? 0} format={fmtInt} />
      <MetricCard
        label="Client errors (4xx)"
        value={u.totalClientErrors ?? 0}
        format={fmtInt}
        invertDelta
      />
      <MetricCard
        label="Server errors (5xx)"
        value={u.totalServerErrors ?? 0}
        format={fmtInt}
        invertDelta
      />
    </div>
  );
}

function buildSeries(
  buckets: { bucketStart?: string; totalCapturedAmountMinor?: number }[] | undefined,
  field: 'totalCapturedAmountMinor',
): number[] {
  const byBucket = new Map<string, number>();
  for (const b of buckets ?? []) {
    byBucket.set(
      (b.bucketStart ?? '') + '',
      (byBucket.get(b.bucketStart ?? '') ?? 0) + (b[field] ?? 0),
    );
  }
  return [...byBucket.entries()].sort((x, y) => x[0].localeCompare(y[0])).map(([, v]) => v);
}

function LineChart({ series }: { series: readonly number[] }) {
  const max = Math.max(...series, 1);
  const w = 1000;
  const h = 180;
  const step = w / Math.max(1, series.length - 1);
  const d = series
    .map(
      (v, i) =>
        `${i ? 'L' : 'M'}${(i * step).toFixed(1)} ${(h - (v / max) * (h - 8) - 4).toFixed(1)}`,
    )
    .join(' ');
  return (
    <div className="h-44">
      <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" className="h-full w-full">
        <line x1="0" y1="1" x2={w} y2="1" stroke="var(--border-subtle)" />
        <line x1="0" y1={h / 2} x2={w} y2={h / 2} stroke="var(--border-subtle)" />
        <line x1="0" y1={h - 1} x2={w} y2={h - 1} stroke="var(--border)" />
        <path
          d={d}
          fill="none"
          stroke="var(--accent-text)"
          strokeWidth="1.5"
          vectorEffect="non-scaling-stroke"
        />
      </svg>
    </div>
  );
}

const fmtInt = (v: number) => String(Math.round(v));

function GridSkeleton({ n }: { n: number }) {
  return (
    <div className="grid gap-3 grid-cols-1 sm:grid-cols-3 lg:grid-cols-4">
      {Array.from({ length: n }, (_, i) => (
        <Skeleton key={i} className="h-24 rounded-lg" />
      ))}
    </div>
  );
}

function ErrLine({ message }: { message: string }) {
  return <p className="text-label text-danger">{message}</p>;
}
