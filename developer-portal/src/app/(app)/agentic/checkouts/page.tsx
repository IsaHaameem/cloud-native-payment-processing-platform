import type { Metadata } from 'next';
import Link from 'next/link';

import { EmptyState } from '@/components/patterns/empty-state';
import { ErrorState } from '@/components/patterns/error-state';
import { PageHeader } from '@/components/patterns/page-header';
import { StatusPill } from '@/components/patterns/status-pill';
import { Card, CardContent } from '@/components/ui/card';
import { loadAgentic } from '@/lib/agentic/load';
import { listCheckouts } from '@/lib/agentic/operations';
import { formatMoney, formatRelativeTime } from '@/lib/format';
import { requireMerchant } from '@/lib/session/require';

export const metadata: Metadata = { title: 'Checkouts' };

export const dynamic = 'force-dynamic';

/**
 * Checkouts the agent assembled — live (M-agentic, G-2).
 *
 * A checkout is a priced, itemised quote. Its total is derived server-side from catalogue prices
 * and is the only number that ever becomes a payment amount — the agent names a checkout, never a
 * price. A checkout locks for the duration of a payment attempt and returns to OPEN if it fails.
 */
export default async function CheckoutsPage() {
  const session = await requireMerchant();
  const result = await loadAgentic(() => listCheckouts(session, { limit: 50 }));

  return (
    <div>
      <PageHeader
        title="Checkouts"
        description="Priced, itemised quotes the agent assembled. Totals are server-derived; the agent cannot set one."
      />

      {result.error ? (
        <ErrorState
          title="Checkouts could not be loaded"
          description={result.error.message}
          code={result.error.code}
          requestId={result.error.requestId}
        />
      ) : result.data.data.length === 0 ? (
        <EmptyState
          title="No checkouts yet"
          description="When the agent builds a quote with create_checkout, it appears here."
        />
      ) : (
        <Card>
          <CardContent className="overflow-x-auto p-0">
            <table className="w-full text-left text-label-sm">
              <thead>
                <tr className="border-b border-border-subtle text-caption uppercase text-fg-subtle">
                  {['Checkout', 'Items', 'Total', 'Status', 'Payment', 'Expires'].map((h) => (
                    <th key={h} className="px-4 py-2.5 font-[510]">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {result.data.data.map((checkout) => (
                  <tr key={checkout.id} className="border-b border-border-subtle last:border-0">
                    <td className="px-4 py-3">
                      <Link
                        href={`/agentic/checkouts/${checkout.id}`}
                        className="font-mono text-accent-text hover:underline"
                      >
                        {checkout.id.slice(0, 8)}…
                      </Link>
                    </td>
                    <td className="px-4 py-3 text-fg-muted">{checkout.lines.length}</td>
                    <td className="px-4 py-3 font-mono text-fg-muted">
                      {formatMoney(checkout.totalMinor, checkout.currency)}
                    </td>
                    <td className="px-4 py-3">
                      <StatusPill status={checkout.status} family="checkout" />
                    </td>
                    <td className="px-4 py-3 font-mono text-caption">
                      {checkout.paymentId ? (
                        <Link
                          href={`/payments/${checkout.paymentId}`}
                          className="text-accent-text hover:underline"
                        >
                          {checkout.paymentId.slice(0, 8)}…
                        </Link>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td className="px-4 py-3 text-fg-subtle">
                      {formatRelativeTime(checkout.expiresAt)}
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
