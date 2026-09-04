import type { Metadata } from 'next';
import Link from 'next/link';

import { DetailList } from '@/components/patterns/detail-list';
import { ErrorState } from '@/components/patterns/error-state';
import { PageHeader } from '@/components/patterns/page-header';
import { StatusPill } from '@/components/patterns/status-pill';
import { Timeline, type TimelineNode } from '@/components/patterns/timeline';
import { Card, CardContent } from '@/components/ui/card';
import { loadAgentic } from '@/lib/agentic/load';
import { getAction } from '@/lib/agentic/operations';
import { formatDateTime } from '@/lib/format';
import { requireMerchant } from '@/lib/session/require';

export const metadata: Metadata = { title: 'Agent action' };

export const dynamic = 'force-dynamic';

/**
 * One action's full trail — live (M-agentic, G-4).
 *
 * The point of the screen is the steps: each platform call the action made, in state
 * {@code SUCCEEDED} or {@code REPLAYED}, carrying the derived idempotency key that makes
 * "the agent did not double-charge" checkable rather than claimed.
 */
export default async function ActionDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const session = await requireMerchant();
  const { id } = await params;
  const result = await loadAgentic(() => getAction(session, id));

  if (result.error) {
    return (
      <div>
        <PageHeader title="Agent action" description={`Action ${id}.`} />
        <ErrorState
          title="This action could not be loaded"
          description={result.error.message}
          code={result.error.code}
          requestId={result.error.requestId}
        />
      </div>
    );
  }

  const action = result.data;
  const steps: TimelineNode[] = action.steps.map((step) => ({
    title: (
      <span className="font-mono text-label-sm text-fg-muted">
        {step.operation} · {step.state}
        {step.httpStatus !== null ? ` · HTTP ${step.httpStatus}` : ''}
      </span>
    ),
    meta: step.idempotencyKey ? (
      <span className="font-mono text-caption break-all text-fg-subtle">
        key {step.idempotencyKey}
      </span>
    ) : undefined,
    body:
      step.requestId || step.paymentId ? (
        <span className="font-mono text-caption text-fg-subtle">
          {step.requestId ? `request ${step.requestId}` : ''}
          {step.requestId && step.paymentId ? ' · ' : ''}
          {step.paymentId ? `payment ${step.paymentId}` : ''}
        </span>
      ) : undefined,
    tone:
      step.state === 'SUCCEEDED'
        ? 'success'
        : step.state === 'REPLAYED'
          ? 'info'
          : step.state === 'FAILED'
            ? 'danger'
            : 'neutral',
  }));

  const executed = action.steps.some((s) => s.state === 'SUCCEEDED' || s.state === 'REPLAYED');
  const pipeline: { label: string; done: boolean; tone: 'ok' | 'gate' | 'stop' | 'idle' }[] = [
    { label: 'Proposed', done: true, tone: 'ok' },
    {
      label: 'Validated',
      done: Boolean(action.inputSummary) || action.state !== 'REJECTED',
      tone: 'ok',
    },
    {
      label:
        action.policyDecision === 'REFUSE'
          ? 'Policy · refused'
          : action.policyDecision === 'REQUIRES_APPROVAL'
            ? 'Policy · needs approval'
            : action.policyDecision
              ? 'Policy · permit'
              : 'Policy',
      done: Boolean(action.policyDecision),
      tone:
        action.policyDecision === 'REFUSE'
          ? 'stop'
          : action.policyDecision === 'REQUIRES_APPROVAL'
            ? 'gate'
            : 'ok',
    },
    ...(action.approvalId
      ? [
          {
            label: executed ? 'Approved' : 'Awaiting approval',
            done: executed,
            tone: (executed ? 'ok' : 'gate') as 'ok' | 'gate',
          },
        ]
      : []),
    {
      label: 'Executed',
      done: executed,
      tone: (action.failureMessage ? 'stop' : executed ? 'ok' : 'idle') as 'ok' | 'stop' | 'idle',
    },
    ...(action.paymentId ? [{ label: 'Payment', done: true, tone: 'ok' as const }] : []),
  ];

  return (
    <div>
      <PageHeader
        title="Agent action"
        description="What the model asked for, what policy decided, and every platform call it produced."
        actions={<StatusPill status={action.state} family="action" />}
      />

      {/* The chain this action passed through: request → proposal → validation → policy →
          approval → execution → payment. Each stage carries its own tone. */}
      <ol className="mb-4 flex flex-wrap items-center gap-x-1.5 gap-y-2 text-label-sm">
        {pipeline.map((stage, i) => (
          <li key={stage.label} className="flex items-center gap-1.5">
            <span
              className={
                'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 ring-1 ring-inset ' +
                (stage.tone === 'ok'
                  ? 'bg-success-surface text-success ring-transparent'
                  : stage.tone === 'gate'
                    ? 'bg-warning-surface text-warning ring-transparent'
                    : stage.tone === 'stop'
                      ? 'bg-danger-surface text-danger ring-transparent'
                      : 'text-fg-subtle ring-border')
              }
            >
              <span
                aria-hidden
                className={
                  'size-1.5 rounded-full ' +
                  (stage.done ? 'bg-current' : 'bg-transparent ring-1 ring-current ring-inset')
                }
              />
              {stage.label}
            </span>
            {i < pipeline.length - 1 ? <span className="text-fg-faint">→</span> : null}
          </li>
        ))}
      </ol>

      <div className="grid gap-4 lg:grid-cols-[1fr_20rem]">
        <div className="space-y-4">
          {action.inputSummary ? (
            <Card>
              <CardContent className="pt-4">
                <h2 className="mb-2 text-label font-[510] text-fg">Validated input (redacted)</h2>
                <p className="rounded-md bg-surface-inset p-2.5 font-mono text-caption break-words text-fg-subtle ring-hairline">
                  {action.inputSummary}
                </p>
              </CardContent>
            </Card>
          ) : null}

          {action.failureMessage ? (
            <Card>
              <CardContent className="py-3 text-label-sm text-danger">
                {action.failureCode ? `${action.failureCode}: ` : ''}
                {action.failureMessage}
              </CardContent>
            </Card>
          ) : null}

          <Card>
            <CardContent className="pt-4">
              <h2 className="mb-3 text-label font-[510] text-fg">Platform calls</h2>
              {steps.length > 0 ? (
                <Timeline nodes={steps} />
              ) : (
                <p className="text-label-sm text-fg-subtle">
                  No platform call was made — the action stopped at policy or approval.
                </p>
              )}
            </CardContent>
          </Card>
        </div>

        <Card className="h-fit">
          <CardContent className="pt-4">
            <DetailList
              rows={[
                { label: 'Tool', value: action.toolName, mono: true },
                { label: 'Category', value: action.toolCategory, mono: true },
                {
                  label: 'Policy',
                  value: action.policyDecision ? (
                    <StatusPill status={action.policyDecision} family="policy" />
                  ) : (
                    '—'
                  ),
                },
                {
                  label: 'Approval',
                  value: action.approvalId ? (
                    <Link
                      href={`/agentic/approvals/${action.approvalId}`}
                      className="text-accent-text hover:underline"
                    >
                      {action.approvalId.slice(0, 8)}…
                    </Link>
                  ) : (
                    '—'
                  ),
                  mono: true,
                },
                {
                  label: 'Payment',
                  value: action.paymentId ? (
                    <Link
                      href={`/payments/${action.paymentId}`}
                      className="text-accent-text hover:underline"
                    >
                      {action.paymentId}
                    </Link>
                  ) : (
                    '—'
                  ),
                  mono: true,
                },
                { label: 'Model', value: action.llmModel ?? '—', mono: true },
                { label: 'Prompt', value: action.promptVersion ?? '—', mono: true },
                { label: 'Correlation', value: action.correlationId ?? '—', mono: true },
                {
                  label: 'Budget left (minor)',
                  value:
                    action.budgetRemainingMinor !== null
                      ? String(action.budgetRemainingMinor)
                      : '—',
                  mono: true,
                },
                { label: 'Created', value: formatDateTime(action.createdAt), mono: true },
                {
                  label: 'Completed',
                  value: action.completedAt ? formatDateTime(action.completedAt) : '—',
                  mono: true,
                },
              ]}
            />
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
