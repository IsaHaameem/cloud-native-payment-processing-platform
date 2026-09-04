import type { Metadata } from 'next';
import Link from 'next/link';

import { EmptyState } from '@/components/patterns/empty-state';
import { ErrorState } from '@/components/patterns/error-state';
import { PageHeader } from '@/components/patterns/page-header';
import { StatusPill } from '@/components/patterns/status-pill';
import { Card, CardContent } from '@/components/ui/card';
import { loadAgentic } from '@/lib/agentic/load';
import { listActions } from '@/lib/agentic/operations';
import { formatRelativeTime } from '@/lib/format';
import { requireMerchant } from '@/lib/session/require';

export const metadata: Metadata = { title: 'Agent actions' };

export const dynamic = 'force-dynamic';

/**
 * The cross-conversation action index — live (M-agentic, G-4).
 *
 * Every tool the agent has run: what the model asked for, what policy decided, whether a human
 * was involved, and the payment or checkout it touched. Open one for the full trail — the
 * platform calls with their derived idempotency keys.
 */
export default async function ActionsPage({
  searchParams,
}: {
  searchParams: Promise<{ payment_id?: string }>;
}) {
  const session = await requireMerchant();
  const { payment_id: paymentId } = await searchParams;
  const result = await loadAgentic(() => listActions(session, { paymentId, limit: 50 }));

  return (
    <div>
      <PageHeader
        title="Agent actions"
        description="The flagship trace: agent request → validation → policy → approval → platform calls → payment → result."
      />

      {result.error ? (
        <ErrorState
          title="Actions could not be loaded"
          description={result.error.message}
          code={result.error.code}
          requestId={result.error.requestId}
        />
      ) : result.data.data.length === 0 ? (
        <EmptyState
          title={paymentId ? 'No actions for that payment' : 'No actions yet'}
          description="Each tool the agent runs is recorded here, before it happens."
        />
      ) : (
        <Card>
          <CardContent className="overflow-x-auto p-0">
            <table className="w-full text-left text-label-sm">
              <thead>
                <tr className="border-b border-border-subtle text-caption uppercase text-fg-subtle">
                  {['Action', 'Tool', 'State', 'Policy', 'Payment', 'When'].map((h) => (
                    <th key={h} className="px-4 py-2.5 font-[510]">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {result.data.data.map((action) => (
                  <tr key={action.id} className="border-b border-border-subtle last:border-0">
                    <td className="px-4 py-3">
                      <Link
                        href={`/agentic/actions/${action.id}`}
                        className="font-mono text-accent-text hover:underline"
                      >
                        {action.id.slice(0, 8)}…
                      </Link>
                    </td>
                    <td className="px-4 py-3 font-mono text-fg-muted">{action.toolName}</td>
                    <td className="px-4 py-3">
                      <StatusPill status={action.state} family="action" />
                    </td>
                    <td className="px-4 py-3">
                      {action.policyDecision ? (
                        <StatusPill status={action.policyDecision} family="policy" />
                      ) : (
                        <span className="text-fg-faint">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3 font-mono text-caption">
                      {action.paymentId ? (
                        <Link
                          href={`/payments/${action.paymentId}`}
                          className="text-accent-text hover:underline"
                        >
                          {action.paymentId.slice(0, 8)}…
                        </Link>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td className="px-4 py-3 text-fg-subtle">
                      {formatRelativeTime(action.createdAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
