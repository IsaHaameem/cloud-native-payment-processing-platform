import type { Metadata } from 'next';
import Link from 'next/link';

import { DetailList } from '@/components/patterns/detail-list';
import { EmptyState } from '@/components/patterns/empty-state';
import { ErrorState } from '@/components/patterns/error-state';
import { PageHeader } from '@/components/patterns/page-header';
import { StatusPill } from '@/components/patterns/status-pill';
import { Timeline, type TimelineNode } from '@/components/patterns/timeline';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { getConversation, getConversationActions } from '@/lib/agentic/operations';
import { loadAgentic } from '@/lib/agentic/load';
import { type AgenticActionTrail } from '@/lib/agentic/types';
import { formatDateTime, formatMoney } from '@/lib/format';
import { requireMerchant } from '@/lib/session/require';

export const metadata: Metadata = { title: 'Conversation' };

export const dynamic = 'force-dynamic';

/**
 * One conversation — live (M-agentic).
 *
 * `frontend_Design.md §20 / §21`. Read-only observability: the transcript, the budget consumed,
 * and the flagship trace — for every action the model proposed, what policy decided, whether a
 * human was involved, and every platform call it produced with the derived idempotency key that
 * proves a retry was a replay, not a second charge. `TOOL` messages render as structured cards,
 * never chat bubbles. Content is stored already redacted.
 */
export default async function ConversationDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const session = await requireMerchant();
  const { id } = await params;

  const [conversationResult, actionsResult] = await Promise.all([
    loadAgentic(() => getConversation(session, id)),
    loadAgentic(() => getConversationActions(session, id)),
  ]);

  if (conversationResult.error) {
    return (
      <div>
        <PageHeader title="Conversation" description={`Conversation ${id}.`} />
        <ErrorState
          title="This conversation could not be loaded"
          description={conversationResult.error.message}
          code={conversationResult.error.code}
          requestId={conversationResult.error.requestId}
        />
      </div>
    );
  }

  const conversation = conversationResult.data;

  return (
    <div>
      <PageHeader
        title="Conversation"
        description="A read-only record of what was said and the chain each tool call went through. You never chat with the agent here."
        actions={<StatusPill status={conversation.status} family="checkout" />}
      />

      <div className="grid gap-4 lg:grid-cols-[1fr_20rem]">
        <div className="space-y-4">
          <Card>
            <CardContent className="pt-4">
              <h2 className="mb-3 text-label font-[510] text-fg">Transcript</h2>
              {conversation.messages.length === 0 ? (
                <p className="text-label-sm text-fg-subtle">No messages yet.</p>
              ) : (
                <ol className="space-y-3">
                  {conversation.messages.map((message) => (
                    <li key={message.sequenceNo}>
                      <div className="mb-1 flex items-center gap-2">
                        <Badge tone={roleTone(message.role)}>{message.role}</Badge>
                        <span className="font-mono text-caption text-fg-subtle">
                          {formatDateTime(message.createdAt)}
                        </span>
                      </div>
                      <p className="rounded-lg bg-surface-inset p-3 text-label-sm whitespace-pre-wrap text-fg-muted ring-hairline">
                        {message.content}
                      </p>
                    </li>
                  ))}
                </ol>
              )}
            </CardContent>
          </Card>

          <section>
            <h2 className="mb-2 text-label font-[510] text-fg">Action trace</h2>
            {actionsResult.error ? (
              <ErrorState
                title="The action trail could not be loaded"
                description={actionsResult.error.message}
                code={actionsResult.error.code}
                requestId={actionsResult.error.requestId}
              />
            ) : actionsResult.data.length === 0 ? (
              <EmptyState
                title="No actions yet"
                description="Each tool the agent runs in this conversation appears here with its policy decision and every platform call it made."
              />
            ) : (
              <div className="space-y-3">
                {actionsResult.data.map((action) => (
                  <ActionCard key={action.id} action={action} />
                ))}
              </div>
            )}
          </section>
        </div>

        <Card className="h-fit">
          <CardContent className="pt-4">
            <h2 className="mb-3 text-label font-[510] text-fg">This conversation</h2>
            <DetailList
              rows={[
                { label: 'Id', value: conversation.id, mono: true },
                { label: 'Session ref', value: conversation.sessionRef, mono: true },
                { label: 'Tool calls', value: String(conversation.toolCallCount), mono: true },
                {
                  label: 'Spent',
                  value: formatMoney(conversation.spentMinor, 'INR'),
                  mono: true,
                },
                {
                  label: 'Refunded',
                  value: formatMoney(conversation.refundedMinor, 'INR'),
                  mono: true,
                },
                { label: 'Started', value: formatDateTime(conversation.createdAt), mono: true },
              ]}
            />
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function ActionCard({ action }: { action: AgenticActionTrail }) {
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

  return (
    <Card>
      <CardContent className="space-y-3 pt-4">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-mono text-label-sm text-fg-muted">{action.toolName}</span>
          <StatusPill status={action.state} family="action" />
          {action.policyDecision ? (
            <StatusPill status={action.policyDecision} family="policy" />
          ) : null}
          {action.approvalId ? (
            <Link
              href={`/agentic/approvals/${action.approvalId}`}
              className="text-label-sm text-accent-text hover:underline"
            >
              approval →
            </Link>
          ) : null}
          {action.paymentId ? (
            <Link
              href={`/payments/${action.paymentId}`}
              className="font-mono text-caption text-accent-text hover:underline"
            >
              payment {action.paymentId.slice(0, 8)}…
            </Link>
          ) : null}
        </div>

        {action.inputSummary ? (
          <p className="rounded-md bg-surface-inset p-2.5 font-mono text-caption break-words text-fg-subtle ring-hairline">
            {action.inputSummary}
          </p>
        ) : null}

        {action.failureMessage ? (
          <p className="text-label-sm text-danger">
            {action.failureCode ? `${action.failureCode}: ` : ''}
            {action.failureMessage}
          </p>
        ) : null}

        {steps.length > 0 ? <Timeline nodes={steps} /> : null}

        <div className="flex flex-wrap gap-x-4 gap-y-1 font-mono text-caption text-fg-subtle">
          {action.llmModel ? <span>model {action.llmModel}</span> : null}
          {action.promptVersion ? <span>prompt {action.promptVersion}</span> : null}
          {action.correlationId ? <span>corr {action.correlationId}</span> : null}
          {action.budgetRemainingMinor !== null ? (
            <span>budget left {action.budgetRemainingMinor}</span>
          ) : null}
        </div>
      </CardContent>
    </Card>
  );
}

function roleTone(role: string): 'neutral' | 'info' | 'accent' {
  const key = role.toUpperCase();
  if (key === 'USER') return 'accent';
  if (key === 'ASSISTANT') return 'info';
  return 'neutral';
}
