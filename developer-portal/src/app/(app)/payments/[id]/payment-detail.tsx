'use client';

import { ArrowLeft } from 'lucide-react';
import Link from 'next/link';
import * as React from 'react';

import { DetailList } from '@/components/patterns/detail-list';
import { ErrorState } from '@/components/patterns/error-state';
import { StatusPill } from '@/components/patterns/status-pill';
import { Timeline, type TimelineNode, type TimelineTone } from '@/components/patterns/timeline';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { CopyField } from '@/components/ui/copy-button';
import { JsonViewer } from '@/components/ui/json-viewer';
import { Skeleton } from '@/components/ui/skeleton';
import type { EventResponse, PaymentResponse, RefundResponse } from '@/generated/models';
import { formatDateTime, formatMoney, formatRelativeTime, truncateId } from '@/lib/format';
import { PlatformRequestError } from '@/lib/query/platform';
import { useQueryScope } from '@/lib/query/scope';
import { usePlatformList, usePlatformQuery } from '@/lib/query/use-platform';

import { PaymentActions } from './payment-actions';

// ── Persisted agent/external provider decisions (G-6) ────────────────────────────────
// The browser reads the portal's own same-origin proxy; merchant + mode come from the
// sealed session, never this component.
type ProviderDecisionKind =
  | 'REAL_AUTHORIZATION'
  | 'DEMO_ORDER_ACCEPTED'
  | 'DECLINED'
  | 'ERRORED'
  | 'NOT_CONFIGURED'
  | (string & {});

interface ProviderDecision {
  readonly id: string | null;
  readonly paymentId: string;
  readonly operation: string;
  readonly outcome: string;
  readonly kind: ProviderDecisionKind;
  readonly demoApproval: boolean;
  readonly source: string;
  readonly declineCode: string | null;
  readonly errorCode: string | null;
  readonly providerReference: string | null;
  readonly providerName: string;
  readonly amountMinor: number;
  readonly currency: string;
  readonly correlationId: string | null;
  readonly createdAt: string;
}

/**
 * The five kinds a persisted provider decision can carry, and how each must read.
 * `DEMO_ORDER_ACCEPTED` is deliberately not `success`: a Razorpay order accepted with no
 * cardholder payment collected is **not** an authorization, and styling it green would be the
 * exact misrepresentation G-6 exists to prevent.
 */
const DECISION_KIND: Record<
  string,
  { label: string; tone: 'success' | 'warning' | 'danger' | 'outline'; blurb: string }
> = {
  REAL_AUTHORIZATION: {
    label: 'Real authorization',
    tone: 'success',
    blurb: 'The provider authorized this instrument for the amount shown.',
  },
  DEMO_ORDER_ACCEPTED: {
    label: 'Demo — order accepted',
    tone: 'warning',
    blurb:
      'A provider order was accepted, but no cardholder payment was collected. This is NOT an authorization and no funds moved — it stands in for one in the demo only.',
  },
  DECLINED: {
    label: 'Declined',
    tone: 'danger',
    blurb: 'The provider declined. The reason code is shown verbatim.',
  },
  ERRORED: {
    label: 'Errored',
    tone: 'danger',
    blurb: 'The provider call failed before a decision was reached.',
  },
  NOT_CONFIGURED: {
    label: 'No external provider',
    tone: 'outline',
    blurb:
      'No external acquirer is connected in this deployment, so the platform’s sandbox acquirer decided this payment.',
  },
};

function useProviderDecisions(paymentId: string): {
  rows: ProviderDecision[];
  loading: boolean;
  failed: boolean;
} {
  const [rows, setRows] = React.useState<ProviderDecision[]>([]);
  const [loading, setLoading] = React.useState(true);
  const [failed, setFailed] = React.useState(false);

  React.useEffect(() => {
    let live = true;
    setLoading(true);
    setFailed(false);
    fetch(`/api/agentic/provider-decisions?payment_id=${encodeURIComponent(paymentId)}`, {
      headers: { accept: 'application/json' },
    })
      .then(async (r) => {
        if (!r.ok) throw new Error(String(r.status));
        const body = (await r.json()) as {
          data?: ProviderDecision[];
          content?: ProviderDecision[];
        };
        return body.data ?? body.content ?? [];
      })
      .then((d) => {
        if (live) setRows(d);
      })
      .catch(() => {
        // The agentic surface is an optional extension — a portal without it is not an error.
        if (live) setFailed(true);
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [paymentId]);

  return { rows, loading, failed };
}

const EVENT_LABEL: Record<string, string> = {
  'payment.created': 'Created',
  'payment.authorized': 'Authorized',
  'payment.captured': 'Captured',
  'payment.failed': 'Failed',
  'payment.refunded': 'Refunded',
  'payment.partially_refunded': 'Partly refunded',
  'payment.voided': 'Voided',
};

const EVENT_TONE: Record<string, TimelineTone> = {
  'payment.created': 'neutral',
  'payment.authorized': 'info',
  'payment.captured': 'success',
  'payment.failed': 'danger',
  'payment.refunded': 'success',
  'payment.partially_refunded': 'warning',
  'payment.voided': 'neutral',
};

export function PaymentDetail({ id, csrfToken }: { id: string; csrfToken: string }) {
  const scope = useQueryScope();

  const query = usePlatformQuery<PaymentResponse>('getPayment', { id, expand: 'refunds' });

  // The contract has no `payment_id` filter on `/v1/events` (the design assumed one). So the
  // recent feed is fetched and narrowed to this payment client-side; when it is not on the
  // recent page, the timeline falls back to one derived from the payment's own fields.
  const events = usePlatformList<EventResponse>('listEvents', { limit: 100 });

  const decisions = usePlatformQuery<{
    content?: Record<string, unknown>[];
    data?: Record<string, unknown>[];
  }>('listSandboxDecisionsForPayment', { paymentId: id }, { enabled: scope.mode === 'test' });

  const providerDecisions = useProviderDecisions(id);

  if (query.isError) {
    const e = query.error;
    const notFound = e instanceof PlatformRequestError && e.status === 404;
    return (
      <div className="py-8">
        <h1 tabIndex={-1} className="sr-only">
          {notFound ? 'Payment not found' : 'Could not load payment'}
        </h1>
        <BackLink />
        <ErrorState
          title={notFound ? 'Payment not found' : 'Could not load this payment'}
          description={
            notFound
              ? 'No payment with this id belongs to your account in this mode. A payment that belongs to another merchant reads as not-found.'
              : e.message
          }
          {...(e instanceof PlatformRequestError && e.requestId ? { requestId: e.requestId } : {})}
          {...(e instanceof PlatformRequestError && e.code ? { code: e.code } : {})}
          onRetry={notFound ? undefined : () => void query.refetch()}
        />
      </div>
    );
  }

  if (query.isPending || !query.data) return <DetailSkeleton />;

  const p = query.data;
  const currency = p.currency ?? 'USD';
  const amount = p.amountMinor ?? 0;
  const captured = p.capturedAmountMinor ?? 0;
  const refunded = p.refundedAmountMinor ?? 0;
  const refundable = Math.max(0, captured - refunded);
  const refunds: RefundResponse[] = p.refunds ?? [];

  const matchedEvents = (events.data?.pages ?? [])
    .flatMap((pg) => pg.data ?? [])
    .filter((ev) => {
      const d = (ev.data ?? {}) as Record<string, unknown>;
      return d.id === id || d.paymentId === id || d.payment_id === id;
    })
    .sort((a, b) => (a.created ?? '').localeCompare(b.created ?? ''));

  const derived = !events.isPending && matchedEvents.length === 0;
  const eventNodes: TimelineNode[] = derived
    ? deriveTimeline(p)
    : matchedEvents.map((ev) => ({
        title: EVENT_LABEL[ev.type ?? ''] ?? ev.type ?? 'Event',
        tone: EVENT_TONE[ev.type ?? ''] ?? 'neutral',
        meta: ev.created ? formatDateTime(ev.created) : undefined,
        body: ev.id ? (
          <span className="font-mono text-caption text-fg-subtle select-all">{ev.id}</span>
        ) : undefined,
      }));

  const decisionRows = decisions.data?.content ?? decisions.data?.data ?? [];
  const firstDecision = decisionRows[0];
  const isDemo = firstDecision?.demo === true;

  return (
    <div className="pb-10">
      <BackLink />

      {/* Header band */}
      <div className="mt-3 flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2.5">
            <h1
              tabIndex={-1}
              className="tabular text-title-1 leading-none font-[510] tracking-[-0.88px] text-fg outline-none"
            >
              {formatMoney(amount, currency)}
              <span className="sr-only"> payment</span>
            </h1>
            <span className="text-label text-fg-subtle">{currency}</span>
            <StatusPill status={p.status} family="payment" dot />
            {p.mode ? (
              <Badge tone={p.mode.toLowerCase() === 'test' ? 'test' : 'outline'}>
                {p.mode.toLowerCase() === 'test' ? 'Test' : 'Live'}
              </Badge>
            ) : null}
          </div>
          <div className="mt-2 flex flex-wrap items-center gap-2 text-label-sm text-fg-subtle">
            {p.id ? <CopyField value={p.id} display={truncateId(p.id)} /> : null}
            {p.createdAt ? <span>{formatRelativeTime(p.createdAt)}</span> : null}
          </div>
        </div>
        <PaymentActions
          id={id}
          csrfToken={csrfToken}
          status={p.status ?? ''}
          currency={currency}
          refundableMinor={refundable}
        />
      </div>

      <div className="mt-6 grid grid-cols-1 gap-4 lg:grid-cols-[2fr_1fr]">
        {/* Left column */}
        <div className="min-w-0 space-y-4">
          <Panel title="Lifecycle">
            {events.isPending ? (
              <Skeleton className="h-24 w-full" />
            ) : eventNodes.length > 0 ? (
              <>
                <Timeline nodes={eventNodes} />
                {derived ? (
                  <p className="mt-3 text-label-sm text-fg-subtle">
                    Derived from the payment’s current state — its events are not on the recent
                    feed, and <code className="font-mono">/v1/events</code> cannot be filtered by
                    payment.
                  </p>
                ) : null}
              </>
            ) : (
              <p className="text-label text-fg-subtle">No lifecycle information available.</p>
            )}
          </Panel>

          <Panel title="Amounts">
            <DetailList
              columns={2}
              rows={[
                { label: 'Authorized', value: formatMoney(amount, currency), mono: true },
                { label: 'Captured', value: formatMoney(captured, currency), mono: true },
                { label: 'Refunded', value: formatMoney(refunded, currency), mono: true },
                {
                  label: 'Refundable remaining',
                  value: formatMoney(refundable, currency),
                  mono: true,
                },
              ]}
            />
          </Panel>

          {refunds.length > 0 ? (
            <Panel title={`Refunds (${refunds.length})`}>
              <div className="overflow-x-auto">
                <table className="w-full min-w-[26rem] text-left">
                  <thead>
                    <tr className="border-b border-border-subtle text-caption tracking-[0.04em] text-fg-subtle uppercase">
                      <th className="py-2 pr-3 font-[510]">Refund</th>
                      <th className="py-2 pr-3 text-right font-[510]">Amount</th>
                      <th className="py-2 pr-3 font-[510]">Status</th>
                      <th className="py-2 font-[510]">Reason</th>
                    </tr>
                  </thead>
                  <tbody>
                    {refunds.map((r) => (
                      <tr key={r.id} className="border-b border-border-subtle last:border-0">
                        <td className="py-2.5 pr-3 font-mono text-label-sm text-fg-muted">
                          {r.id ? truncateId(r.id) : '—'}
                        </td>
                        <td className="tabular py-2.5 pr-3 text-right text-label">
                          {r.amountMinor !== undefined
                            ? formatMoney(r.amountMinor, r.currency ?? currency)
                            : '—'}
                        </td>
                        <td className="py-2.5 pr-3">
                          <StatusPill status={r.status} family="refund" dot />
                        </td>
                        <td className="py-2.5 text-label-sm text-fg-subtle">{r.reason ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Panel>
          ) : null}

          {p.status?.toLowerCase() === 'failed' ? (
            <Panel title="Failure">
              <p className="text-label text-fg-subtle">
                The acquirer’s own reason code, shown verbatim.
              </p>
              <p className="mt-2 rounded-md bg-surface-inset px-3 py-2 font-mono text-label text-danger ring-hairline">
                {p.failureReason ?? 'unknown'}
              </p>
            </Panel>
          ) : null}

          {providerDecisions.rows.length > 0 ? (
            <Panel title="Provider decision">
              <p className="mb-3 text-label-sm text-fg-subtle">
                Persisted by <code className="font-mono">agentic-commerce-service</code> (G-6) when
                a payment is routed to an external provider. The{' '}
                <span className="font-[510]">kind</span> is derived server-side so a demo order can
                never be read as a real authorization.
              </p>
              <div className="space-y-3">
                {providerDecisions.rows.map((d, i) => {
                  const meta = DECISION_KIND[d.kind] ?? {
                    label: d.kind,
                    tone: 'outline' as const,
                    blurb: '',
                  };
                  return (
                    <div key={d.id ?? i} className="rounded-md ring-hairline" data-kind={d.kind}>
                      <div className="flex flex-wrap items-center gap-2 border-b border-border-subtle px-3 py-2">
                        <Badge tone={meta.tone}>{meta.label}</Badge>
                        <span className="font-mono text-caption text-fg-subtle uppercase">
                          {d.operation}
                        </span>
                        <span className="tabular ml-auto text-label-sm text-fg-muted">
                          {formatMoney(d.amountMinor, d.currency)}
                        </span>
                      </div>
                      <div className="space-y-2 px-3 py-2.5">
                        {meta.blurb ? (
                          <p
                            className={
                              d.kind === 'DEMO_ORDER_ACCEPTED'
                                ? 'rounded-md border border-mode-test-border bg-mode-test-surface px-2.5 py-2 text-label-sm text-fg-muted'
                                : 'text-label-sm text-fg-subtle'
                            }
                          >
                            {meta.blurb}
                          </p>
                        ) : null}
                        <DetailList
                          rows={[
                            { label: 'Provider', value: d.providerName || '—', mono: true },
                            { label: 'Outcome', value: d.outcome || '—', mono: true },
                            ...(d.declineCode
                              ? [{ label: 'Decline code', value: d.declineCode, mono: true }]
                              : []),
                            ...(d.errorCode
                              ? [{ label: 'Error code', value: d.errorCode, mono: true }]
                              : []),
                            ...(d.providerReference
                              ? [{ label: 'Provider ref', value: d.providerReference, mono: true }]
                              : []),
                            {
                              label: 'Correlation',
                              value: d.correlationId ?? '—',
                              mono: true,
                            },
                            { label: 'Recorded', value: formatDateTime(d.createdAt) },
                          ]}
                        />
                      </div>
                    </div>
                  );
                })}
              </div>
            </Panel>
          ) : null}

          <Panel title="Sandbox acquirer decision">
            {scope.mode !== 'test' ? (
              <p className="text-label-sm text-fg-subtle">
                Live-mode authorization in this deployment is a{' '}
                <span className="font-[510]">simulated</span> stochastic acquirer — there is no
                external acquirer connected and no real money moves. Its per-payment decision is not
                persisted, so there is nothing to show here for a live payment.
              </p>
            ) : decisions.isPending ? (
              <Skeleton className="h-16 w-full" />
            ) : firstDecision ? (
              <>
                <p className="mb-3 text-label-sm text-fg-subtle">
                  The sandboxed acquirer’s own decision for this test payment. No real funds move in
                  test mode.
                </p>
                {isDemo ? (
                  <div className="mb-3 rounded-md border border-mode-test-border bg-mode-test-surface p-3 text-label-sm text-fg-muted">
                    This is a <span className="font-mono text-mode-test">demo</span> decision. No
                    card was charged; it is never an authorization of real funds.
                  </div>
                ) : null}
                <JsonViewer data={firstDecision} />
              </>
            ) : (
              <p className="text-label text-fg-subtle">
                No sandbox decision recorded for this payment.
              </p>
            )}
          </Panel>
        </div>

        {/* Right column */}
        <div className="min-w-0 space-y-4">
          <Panel title="Related">
            <ul className="space-y-1 text-label">
              <RelatedLink
                href={`/developers/events?payment_id=${encodeURIComponent(id)}`}
                label="Events"
                count={eventNodes.length}
              />
              <RelatedLink href="/balance" label="Balance transactions" />
              <RelatedLink href={`/developers/logs`} label="Request logs" />
              <RelatedLink href="/refunds" label="Refunds" count={refunds.length} />
            </ul>
          </Panel>

          {p.metadata && Object.keys(p.metadata).length > 0 ? (
            <Panel title="Metadata">
              <dl className="space-y-1.5">
                {Object.entries(p.metadata).map(([k, v]) => (
                  <div key={k} className="flex gap-2 font-mono text-label-sm">
                    <dt className="text-fg-subtle">{k}</dt>
                    <dd className="break-all text-fg-muted">{v}</dd>
                  </div>
                ))}
              </dl>
            </Panel>
          ) : null}

          <Panel title="Technical">
            <DetailList
              rows={[
                { label: 'ID', value: p.id ?? '—', mono: true },
                { label: 'Merchant', value: p.merchantId ?? '—', mono: true },
                { label: 'Mode', value: p.mode ?? '—', mono: true },
                {
                  label: 'Method token',
                  value: p.paymentMethodToken ?? '—',
                  mono: true,
                },
                {
                  label: 'Created',
                  value: p.createdAt ? formatDateTime(p.createdAt) : '—',
                },
                {
                  label: 'Updated',
                  value: p.updatedAt ? formatDateTime(p.updatedAt) : '—',
                },
              ]}
            />
          </Panel>
        </div>
      </div>
    </div>
  );
}

/** A minimal timeline built from the payment's own fields, when its events are not reachable. */
function deriveTimeline(p: PaymentResponse): TimelineNode[] {
  const nodes: TimelineNode[] = [
    {
      title: 'Created',
      tone: 'neutral',
      meta: p.createdAt ? formatDateTime(p.createdAt) : undefined,
    },
  ];
  const status = p.status?.toLowerCase();
  if (status === 'failed') {
    nodes.push({ title: 'Failed', tone: 'danger', body: p.failureReason });
  } else if (status === 'voided') {
    nodes.push({ title: 'Voided', tone: 'neutral' });
  } else {
    if (['authorized', 'captured', 'partially_refunded', 'refunded'].includes(status ?? '')) {
      nodes.push({ title: 'Authorized', tone: 'info' });
    }
    if ((p.capturedAmountMinor ?? 0) > 0) {
      nodes.push({ title: 'Captured', tone: 'success' });
    }
    if ((p.refundedAmountMinor ?? 0) > 0) {
      nodes.push({
        title: status === 'refunded' ? 'Refunded' : 'Partly refunded',
        tone: status === 'refunded' ? 'success' : 'warning',
        meta: p.updatedAt ? formatDateTime(p.updatedAt) : undefined,
      });
    }
  }
  return nodes;
}

function BackLink() {
  return (
    <Link
      href="/payments"
      className="inline-flex items-center gap-1.5 text-label text-fg-subtle transition-colors hover:text-fg"
    >
      <ArrowLeft className="size-3.5" /> Payments
    </Link>
  );
}

function Panel({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <Card>
      <CardContent className="pt-4">
        <h2 className="mb-3 text-label font-[510] text-fg">{title}</h2>
        {children}
      </CardContent>
    </Card>
  );
}

function RelatedLink({ href, label, count }: { href: string; label: string; count?: number }) {
  return (
    <li>
      <Link
        href={href}
        className="flex items-center justify-between rounded-md px-2 py-1.5 -mx-2 text-fg-muted transition-colors hover:bg-surface-hover hover:text-fg"
      >
        <span>{label}</span>
        {count !== undefined ? (
          <span className="tabular text-label-sm text-fg-subtle">{count}</span>
        ) : (
          <span className="text-fg-faint">→</span>
        )}
      </Link>
    </li>
  );
}

function DetailSkeleton() {
  return (
    <div className="pb-10">
      <h1 tabIndex={-1} className="sr-only">
        Loading payment
      </h1>
      <Skeleton className="h-4 w-24" />
      <div className="mt-4 flex items-center gap-3">
        <Skeleton className="h-9 w-40" />
        <Skeleton className="h-5 w-20 rounded-full" />
      </div>
      <div className="mt-6 grid grid-cols-1 gap-4 lg:grid-cols-[2fr_1fr]">
        <div className="space-y-4">
          <Skeleton className="h-40 w-full rounded-lg" />
          <Skeleton className="h-28 w-full rounded-lg" />
        </div>
        <div className="space-y-4">
          <Skeleton className="h-32 w-full rounded-lg" />
          <Skeleton className="h-40 w-full rounded-lg" />
        </div>
      </div>
    </div>
  );
}
