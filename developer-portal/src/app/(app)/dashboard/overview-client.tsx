'use client';

import { AlertCircle, ArrowRight } from 'lucide-react';
import Link from 'next/link';
import * as React from 'react';

import { BackendGapNotice } from '@/components/patterns/backend-gap';
import { MetricCard } from '@/components/patterns/metric-card';
import { StatusPill } from '@/components/patterns/status-pill';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import type {
  AnalyticsSummaryResponse,
  PaymentResponse,
  WebhookEndpointResponse,
} from '@/generated/models';
import { formatMoney, formatRelativeTime, truncateId } from '@/lib/format';
import type { Mode } from '@/lib/session/session';
import { usePlatformList, usePlatformQuery } from '@/lib/query/use-platform';

import { GettingStarted } from './getting-started';

const RANGES = [
  { id: '24h', label: '24h', hours: 24 },
  { id: '7d', label: '7d', hours: 24 * 7 },
  { id: '30d', label: '30d', hours: 24 * 30 },
] as const;

export function OverviewClient({ mode }: { mode: Mode }) {
  const [rangeId, setRangeId] = React.useState<(typeof RANGES)[number]['id']>('24h');
  const range = RANGES.find((r) => r.id === rangeId) ?? RANGES[0];

  const { from, to } = React.useMemo(() => {
    const now = new Date();
    const start = new Date(now.getTime() - range.hours * 3_600_000);
    return { from: start.toISOString(), to: now.toISOString() };
  }, [range.hours]);

  const analytics = usePlatformQuery<AnalyticsSummaryResponse>('getPaymentAnalytics', { from, to });
  const recent = usePlatformList<PaymentResponse>('listPayments', { limit: 5 });
  const failures = usePlatformList<PaymentResponse>('listPayments', { status: 'failed', limit: 5 });
  const endpoints = usePlatformQuery<
    WebhookEndpointResponse[] | { data?: WebhookEndpointResponse[] }
  >('listWebhookEndpoints');

  const a = analytics.data;
  const noActivity =
    analytics.isSuccess &&
    (a?.createdCount ?? 0) === 0 &&
    (a?.capturedCount ?? 0) === 0 &&
    (a?.failedCount ?? 0) === 0;

  // A single captured-volume series across the window, summed over currencies per bucket.
  const series = React.useMemo(() => {
    const byBucket = new Map<string, number>();
    for (const b of a?.buckets ?? []) {
      const key = b.bucketStart ?? '';
      byBucket.set(key, (byBucket.get(key) ?? 0) + (b.totalCapturedAmountMinor ?? 0));
    }
    return [...byBucket.entries()].sort((x, y) => x[0].localeCompare(y[0])).map(([, v]) => v);
  }, [a?.buckets]);

  // The aggregate totals are not currency-split by the contract; the buckets are. Format the
  // aggregates in the currency the buckets predominantly use, falling back to USD.
  const primaryCurrency = React.useMemo(() => {
    const counts = new Map<string, number>();
    for (const b of a?.buckets ?? []) {
      if (b.currency) counts.set(b.currency, (counts.get(b.currency) ?? 0) + 1);
    }
    return [...counts.entries()].sort((x, y) => y[1] - x[1])[0]?.[0] ?? 'USD';
  }, [a?.buckets]);

  if (analytics.isError) {
    return (
      <div className="flex items-start gap-3 rounded-lg bg-surface p-5 ring-hairline">
        <AlertCircle className="mt-0.5 size-4 shrink-0 text-danger" aria-hidden />
        <div className="min-w-0 flex-1">
          <p className="text-label font-[510] text-fg">Could not load analytics</p>
          <p className="mt-1 text-label text-fg-subtle">{analytics.error.message}</p>
          <Button
            variant="secondary"
            size="sm"
            className="mt-4"
            onClick={() => void analytics.refetch()}
          >
            Try again
          </Button>
        </div>
      </div>
    );
  }

  if (noActivity) return <GettingStarted mode={mode} />;

  const captured = a?.totalCapturedAmountMinor ?? 0;
  const refunded = a?.totalRefundedAmountMinor ?? 0;
  const successRate = a?.successRate;

  return (
    <div className="flex flex-col gap-5">
      {/* Range selector */}
      <div className="flex items-center gap-1 self-start rounded-md bg-surface-inset p-1 ring-hairline">
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

      {/* KPI row */}
      {analytics.isPending ? (
        <div className="grid gap-3 grid-cols-1 sm:grid-cols-2 lg:grid-cols-5">
          {Array.from({ length: 5 }, (_, i) => (
            <Skeleton key={i} className="h-24 rounded-lg" />
          ))}
        </div>
      ) : (
        <div className="grid gap-3 grid-cols-1 sm:grid-cols-2 lg:grid-cols-5">
          <MetricCard
            label="Captured volume"
            value={captured}
            format={(v) => formatMoney(Math.round(v), primaryCurrency)}
            series={series.length > 1 ? series : undefined}
          />
          <MetricCard
            label="Success rate"
            value={successRate == null ? 0 : successRate * 100}
            format={(v) => (successRate == null ? '—' : `${v.toFixed(1)}%`)}
          />
          <MetricCard
            label="Payments created"
            value={a?.createdCount ?? 0}
            format={(v) => String(Math.round(v))}
          />
          <MetricCard
            label="Failed"
            value={a?.failedCount ?? 0}
            format={(v) => String(Math.round(v))}
            invertDelta
          />
          <MetricCard
            label="Refunded"
            value={refunded}
            format={(v) => formatMoney(Math.round(v), primaryCurrency)}
            hint={`${a?.refundedCount ?? 0} refunds`}
          />
        </div>
      )}

      {series.length > 1 ? (
        <Card>
          <CardContent className="pt-4">
            <h2 className="mb-3 text-label font-[510] text-fg">Captured volume over time</h2>
            <VolumeChart series={series} />
          </CardContent>
        </Card>
      ) : null}

      {/* Recent payments + failures */}
      <div className="grid gap-4 grid-cols-1 lg:grid-cols-2">
        <RecentList
          title="Recent payments"
          href="/payments"
          rows={(recent.data?.pages ?? []).flatMap((p) => p.data ?? [])}
          loading={recent.isPending}
          emptyLabel="No payments yet."
        />
        <RecentList
          title="Recent failures"
          href="/payments?status=failed"
          rows={(failures.data?.pages ?? []).flatMap((p) => p.data ?? [])}
          loading={failures.isPending}
          emptyLabel="No failed payments — good."
          failureMode
        />
      </div>

      {/* Agent activity + operational alerts */}
      <div className="grid gap-4 grid-cols-1 lg:grid-cols-2">
        <Card>
          <CardContent className="pt-4">
            <h2 className="mb-3 text-label font-[510] text-fg">Agent activity</h2>
            <BackendGapNotice id="G-1" title="No aggregate metrics endpoint">
              The agentic service emits conversations, tool calls, policy decisions and approvals as
              Prometheus counters (<code>agentic_*_total</code>), but there is no merchant-facing
              JSON endpoint to read them from. Pending approvals are live on the{' '}
              <Link href="/agentic/approvals" className="text-accent-text hover:underline">
                Approvals
              </Link>{' '}
              screen.
            </BackendGapNotice>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="pt-4">
            <h2 className="mb-3 text-label font-[510] text-fg">Operational alerts</h2>
            <OperationalAlerts endpoints={endpoints.data} loading={endpoints.isPending} />
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function VolumeChart({ series }: { series: readonly number[] }) {
  const max = Math.max(...series, 1);
  const w = 1000;
  const h = 160;
  const step = w / Math.max(1, series.length - 1);
  const path = series
    .map(
      (v, i) =>
        `${i === 0 ? 'M' : 'L'}${(i * step).toFixed(1)} ${(h - (v / max) * (h - 8) - 4).toFixed(1)}`,
    )
    .join(' ');
  return (
    <div className="relative h-40">
      <svg
        viewBox={`0 0 ${w} ${h}`}
        preserveAspectRatio="none"
        className="absolute inset-0 h-full w-full"
      >
        <line x1="0" y1="1" x2={w} y2="1" stroke="var(--border-subtle)" />
        <line x1="0" y1={h / 2} x2={w} y2={h / 2} stroke="var(--border-subtle)" />
        <line x1="0" y1={h - 1} x2={w} y2={h - 1} stroke="var(--border)" />
        <path
          d={path}
          fill="none"
          stroke="var(--accent-text)"
          strokeWidth="1.5"
          vectorEffect="non-scaling-stroke"
        />
      </svg>
    </div>
  );
}

function RecentList({
  title,
  href,
  rows,
  loading,
  emptyLabel,
  failureMode = false,
}: {
  title: string;
  href: string;
  rows: PaymentResponse[];
  loading: boolean;
  emptyLabel: string;
  failureMode?: boolean;
}) {
  return (
    <Card>
      <CardContent className="p-0">
        <div className="flex items-center justify-between border-b border-border-subtle px-4 py-3">
          <h2 className="text-label font-[510] text-fg">{title}</h2>
          <Link
            href={href}
            className="inline-flex items-center gap-1 text-label-sm text-accent-text hover:underline"
          >
            View all <ArrowRight className="size-3" />
          </Link>
        </div>
        {loading ? (
          <div className="space-y-2 p-4">
            {Array.from({ length: 4 }, (_, i) => (
              <Skeleton key={i} className="h-8 w-full" />
            ))}
          </div>
        ) : rows.length === 0 ? (
          <p className="px-4 py-6 text-label text-fg-subtle">{emptyLabel}</p>
        ) : (
          <ul className="divide-y divide-border-subtle">
            {rows.map((p) => (
              <li key={p.id}>
                <Link
                  href={p.id ? `/payments/${encodeURIComponent(p.id)}` : href}
                  className="flex items-center justify-between gap-3 px-4 py-2.5 transition-colors hover:bg-surface-hover"
                >
                  <span className="min-w-0">
                    <span className="block font-mono text-label-sm text-fg-muted">
                      {p.id ? truncateId(p.id) : '—'}
                    </span>
                    {failureMode && p.failureReason ? (
                      <span className="block font-mono text-caption text-danger">
                        {p.failureReason}
                      </span>
                    ) : (
                      <span className="block text-caption text-fg-subtle">
                        {p.createdAt ? formatRelativeTime(p.createdAt) : ''}
                      </span>
                    )}
                  </span>
                  <span className="flex shrink-0 items-center gap-2">
                    <span className="tabular text-label-sm text-fg">
                      {p.amountMinor !== undefined
                        ? formatMoney(p.amountMinor, p.currency ?? 'USD')
                        : '—'}
                    </span>
                    {!failureMode ? <StatusPill status={p.status} family="payment" dot /> : null}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

function OperationalAlerts({
  endpoints,
  loading,
}: {
  endpoints: WebhookEndpointResponse[] | { data?: WebhookEndpointResponse[] } | undefined;
  loading: boolean;
}) {
  if (loading) return <Skeleton className="h-16 w-full" />;

  const list = Array.isArray(endpoints) ? endpoints : (endpoints?.data ?? []);
  const failing = list.filter(
    (e) => (e.consecutiveFailureCount ?? 0) > 0 || e.disabledAt || e.enabled === false,
  );

  if (failing.length === 0) {
    return (
      <p className="text-label text-fg-subtle">
        Nothing needs attention. Webhook endpoints are healthy and no endpoint is disabled.
      </p>
    );
  }

  return (
    <ul className="space-y-3">
      {failing.map((e) => (
        <li key={e.id} className="flex items-start gap-2.5">
          <span
            aria-hidden
            className={`mt-1.5 size-1.5 shrink-0 rounded-full ${
              e.disabledAt ? 'bg-danger' : 'bg-warning'
            }`}
          />
          <div className="min-w-0">
            <p className="text-label text-fg">
              {e.disabledAt
                ? 'Endpoint auto-disabled after repeated failures'
                : `${e.consecutiveFailureCount} consecutive delivery failures`}
            </p>
            <p className="font-mono text-label-sm break-all text-fg-subtle">
              {e.description ?? e.id}
            </p>
          </div>
          <Link
            href="/developers/webhooks"
            className="ml-auto shrink-0 text-label-sm text-accent-text hover:underline"
          >
            Inspect
          </Link>
        </li>
      ))}
    </ul>
  );
}
