import type { Metadata } from 'next';
import Link from 'next/link';

import { EmptyState } from '@/components/patterns/empty-state';
import { ErrorState } from '@/components/patterns/error-state';
import { PageHeader } from '@/components/patterns/page-header';
import { StatusPill } from '@/components/patterns/status-pill';
import { Card, CardContent } from '@/components/ui/card';
import { loadAgentic } from '@/lib/agentic/load';
import { listConversations } from '@/lib/agentic/operations';
import { formatMoney, formatRelativeTime } from '@/lib/format';
import { requireMerchant } from '@/lib/session/require';

import { OpenById } from '../open-by-id';

export const metadata: Metadata = { title: 'Conversations' };

export const dynamic = 'force-dynamic';

/**
 * Conversations the agent has held — live (M-agentic, G-4).
 *
 * A read-only observability record: what the agent and a buyer said, and the chain each tool call
 * went through. You never chat with the agent here.
 */
export default async function ConversationsPage() {
  const session = await requireMerchant();
  const result = await loadAgentic(() => listConversations(session, { limit: 50 }));

  return (
    <div>
      <PageHeader
        title="Conversations"
        description="A read-only record of what the agent and a buyer said, and the chain each tool call went through."
      />

      {result.error ? (
        <ErrorState
          title="Conversations could not be loaded"
          description={result.error.message}
          code={result.error.code}
          requestId={result.error.requestId}
        />
      ) : result.data.data.length === 0 ? (
        <EmptyState
          title="No conversations yet"
          description="Start one from the Agent screen's test console, or through the API."
        />
      ) : (
        <Card>
          <CardContent className="overflow-x-auto p-0">
            <table className="w-full text-left text-label-sm">
              <thead>
                <tr className="border-b border-border-subtle text-caption uppercase text-fg-subtle">
                  {['Conversation', 'Status', 'Tool calls', 'Spent', 'Refunded', 'Started'].map(
                    (h) => (
                      <th key={h} className="px-4 py-2.5 font-[510]">
                        {h}
                      </th>
                    ),
                  )}
                </tr>
              </thead>
              <tbody>
                {result.data.data.map((conversation) => (
                  <tr key={conversation.id} className="border-b border-border-subtle last:border-0">
                    <td className="px-4 py-3">
                      <Link
                        href={`/agentic/conversations/${conversation.id}`}
                        className="font-mono text-accent-text hover:underline"
                      >
                        {conversation.id.slice(0, 8)}…
                      </Link>
                      <p className="font-mono text-caption text-fg-subtle">
                        {conversation.sessionRef}
                      </p>
                    </td>
                    <td className="px-4 py-3">
                      <StatusPill status={conversation.status} family="checkout" />
                    </td>
                    <td className="px-4 py-3 font-mono text-fg-muted">
                      {conversation.toolCallCount}
                    </td>
                    <td className="px-4 py-3 font-mono text-fg-muted">
                      {formatMoney(conversation.spentMinor, 'INR')}
                    </td>
                    <td className="px-4 py-3 font-mono text-fg-muted">
                      {formatMoney(conversation.refundedMinor, 'INR')}
                    </td>
                    <td className="px-4 py-3 text-fg-subtle">
                      {formatRelativeTime(conversation.createdAt)}
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
          base="/agentic/conversations"
          placeholder="87897763-6b0e-4c11-…"
          label="Open a conversation by id"
        />
      </div>
    </div>
  );
}
