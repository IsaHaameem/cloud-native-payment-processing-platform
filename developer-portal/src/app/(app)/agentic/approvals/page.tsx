import type { Metadata } from 'next';
import Link from 'next/link';

import { ErrorState } from '@/components/patterns/error-state';
import { EmptyState } from '@/components/patterns/empty-state';
import { PageHeader } from '@/components/patterns/page-header';
import { StatusPill } from '@/components/patterns/status-pill';
import { Card, CardContent } from '@/components/ui/card';
import { listApprovals } from '@/lib/agentic/operations';
import { loadAgentic } from '@/lib/agentic/load';
import { formatMoney, formatRelativeTime } from '@/lib/format';
import { requireMerchant } from '@/lib/session/require';

import { OpenById } from '../open-by-id';

export const metadata: Metadata = { title: 'Approvals' };

export const dynamic = 'force-dynamic';

/**
 * The approvals inbox — live (M-agentic).
 *
 * `frontend_Design.md §19`, the highest-stakes screen. The queue is `GET /api/agentic/approvals`,
 * reached through the portal's signed server-side proxy — the agentic service authenticates the
 * internal context the proxy asserts from the session. An approval binds seven fields and
 * expires at its TTL whether or not it was granted; a decision happens on the detail page, where
 * the confirmation is.
 */
export default async function ApprovalsPage() {
  const session = await requireMerchant();
  const result = await loadAgentic(() => listApprovals(session));

  return (
    <div>
      <PageHeader
        title="Approvals"
        description="Agent-initiated actions above the policy threshold, waiting for a person. Approving executes the action; the agentic service re-checks policy and re-derives every bound field first."
      />

      {result.error ? (
        <ErrorState
          title="The approval queue could not be loaded"
          description={result.error.message}
          code={result.error.code}
          requestId={result.error.requestId}
        />
      ) : result.data.length === 0 ? (
        <EmptyState
          title="Nothing waiting"
          description="When the agent proposes a money action above the policy threshold, it lands here for a person to approve or deny."
        />
      ) : (
        <Card>
          <CardContent className="overflow-x-auto p-0">
            <table className="w-full text-left text-label-sm">
              <thead>
                <tr className="border-b border-border-subtle text-caption uppercase text-fg-subtle">
                  {['Approval', 'Operation', 'Amount', 'State', 'Expires'].map((h) => (
                    <th key={h} className="px-4 py-2.5 font-[510]">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {result.data.map((approval) => (
                  <tr key={approval.id} className="border-b border-border-subtle last:border-0">
                    <td className="px-4 py-3">
                      <Link
                        href={`/agentic/approvals/${approval.id}`}
                        className="font-mono text-accent-text hover:underline"
                      >
                        {approval.id.slice(0, 8)}…
                      </Link>
                      <p className="mt-0.5 font-mono text-caption text-fg-subtle">
                        {approval.toolName}
                      </p>
                    </td>
                    <td className="px-4 py-3 text-fg-muted">{approval.operation}</td>
                    <td className="px-4 py-3 font-mono text-fg-muted">
                      {approval.amountMinor !== null && approval.currency
                        ? formatMoney(approval.amountMinor, approval.currency)
                        : '—'}
                    </td>
                    <td className="px-4 py-3">
                      <StatusPill status={approval.state} family="approval" />
                    </td>
                    <td className="px-4 py-3 text-fg-subtle">
                      {formatRelativeTime(approval.expiresAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardContent>
        </Card>
      )}

      <div className="mt-4">
        <OpenById
          base="/agentic/approvals"
          placeholder="87897763-6b0e-4c11-…"
          label="Open an approval by id"
        />
      </div>
    </div>
  );
}
