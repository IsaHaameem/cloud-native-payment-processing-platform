import type { Metadata } from 'next';
import Link from 'next/link';

import { DetailList } from '@/components/patterns/detail-list';
import { ErrorState } from '@/components/patterns/error-state';
import { PageHeader } from '@/components/patterns/page-header';
import { StatusPill } from '@/components/patterns/status-pill';
import { Card, CardContent } from '@/components/ui/card';
import { getApproval } from '@/lib/agentic/operations';
import { loadAgentic } from '@/lib/agentic/load';
import { formatDateTime, formatMoney } from '@/lib/format';
import { readCsrfToken } from '@/lib/security/csrf';
import { requireMerchant } from '@/lib/session/require';

import { ApprovalDecision } from './decision';

export const metadata: Metadata = { title: 'Approval' };

export const dynamic = 'force-dynamic';

/**
 * One approval — live (M-agentic).
 *
 * `frontend_Design.md §19`. Shows the seven bound fields the approval froze at request time, and,
 * while it is still `PENDING`, the decision controls. Approve and deny are Server Actions
 * (`./actions`), so the CSRF token and the confirmation live in the form. Approve is a single
 * operation that also executes: the agentic service re-derives every bound field and refuses on
 * the first that moved.
 */
export default async function ApprovalDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const session = await requireMerchant();
  const { id } = await params;
  const [result, csrfToken] = await Promise.all([
    loadAgentic(() => getApproval(session, id)),
    readCsrfToken(),
  ]);

  if (result.error) {
    return (
      <div>
        <PageHeader title="Approval" description={`Approval ${id}.`} />
        <ErrorState
          title="This approval could not be loaded"
          description={result.error.message}
          code={result.error.code}
          requestId={result.error.requestId}
        />
      </div>
    );
  }

  const approval = result.data;
  const pending = approval.state.toUpperCase() === 'PENDING';

  return (
    <div>
      <PageHeader
        title="Approval"
        description="What the agent asked for, what it will cost, and — while it is pending — the decision."
        actions={<StatusPill status={approval.state} family="approval" />}
      />

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardContent className="pt-4">
            <h2 className="mb-3 text-label font-[510] text-fg">What was requested</h2>
            <DetailList
              rows={[
                { label: 'Tool', value: approval.toolName, mono: true },
                { label: 'Operation', value: approval.operation, mono: true },
                {
                  label: 'Amount',
                  value:
                    approval.amountMinor !== null && approval.currency
                      ? formatMoney(approval.amountMinor, approval.currency)
                      : '—',
                  mono: true,
                },
                {
                  label: 'Payment',
                  value: approval.paymentId ? (
                    <Link
                      href={`/payments/${approval.paymentId}`}
                      className="text-accent-text hover:underline"
                    >
                      {approval.paymentId}
                    </Link>
                  ) : (
                    '—'
                  ),
                  mono: true,
                },
                { label: 'Checkout', value: approval.checkoutId ?? '—', mono: true },
              ]}
            />
          </CardContent>
        </Card>

        <Card>
          <CardContent className="pt-4">
            <h2 className="mb-3 text-label font-[510] text-fg">Lifecycle</h2>
            <DetailList
              rows={[
                {
                  label: 'Conversation',
                  value: (
                    <Link
                      href={`/agentic/conversations/${approval.conversationId}`}
                      className="text-accent-text hover:underline"
                    >
                      {approval.conversationId}
                    </Link>
                  ),
                  mono: true,
                },
                { label: 'Agent action', value: approval.agentActionId, mono: true },
                { label: 'Requested', value: formatDateTime(approval.createdAt), mono: true },
                { label: 'Expires', value: formatDateTime(approval.expiresAt), mono: true },
                {
                  label: 'Decided',
                  value: approval.decidedAt ? formatDateTime(approval.decidedAt) : '—',
                  mono: true,
                },
                { label: 'Decided by', value: approval.decidedBy ?? '—' },
                { label: 'Reason', value: approval.reason ?? '—' },
              ]}
            />
          </CardContent>
        </Card>
      </div>

      <div className="mt-4">
        {pending ? (
          <ApprovalDecision approvalId={approval.id} csrfToken={csrfToken} />
        ) : (
          <Card>
            <CardContent className="py-4 text-label-sm text-fg-subtle">
              This approval is <span className="font-[510] text-fg-muted">{approval.state}</span>.
              There is nothing to decide — an approval is single-use and expires at its TTL whether
              or not it was ever granted.
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  );
}
