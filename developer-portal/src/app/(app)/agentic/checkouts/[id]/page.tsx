import type { Metadata } from 'next';
import Link from 'next/link';

import { DetailList } from '@/components/patterns/detail-list';
import { ErrorState } from '@/components/patterns/error-state';
import { PageHeader } from '@/components/patterns/page-header';
import { StatusPill } from '@/components/patterns/status-pill';
import { Card, CardContent } from '@/components/ui/card';
import { loadAgentic } from '@/lib/agentic/load';
import { getCheckout } from '@/lib/agentic/operations';
import { formatDateTime, formatMoney } from '@/lib/format';
import { requireMerchant } from '@/lib/session/require';

export const metadata: Metadata = { title: 'Checkout' };

export const dynamic = 'force-dynamic';

/** One checkout, with its priced lines — live (M-agentic, G-2). */
export default async function CheckoutDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const session = await requireMerchant();
  const { id } = await params;
  const result = await loadAgentic(() => getCheckout(session, id));

  if (result.error) {
    return (
      <div>
        <PageHeader title="Checkout" description={`Checkout ${id}.`} />
        <ErrorState
          title="This checkout could not be loaded"
          description={result.error.message}
          code={result.error.code}
          requestId={result.error.requestId}
        />
      </div>
    );
  }

  const checkout = result.data;

  return (
    <div>
      <PageHeader
        title="Checkout"
        description="A priced, itemised quote. Each line's price was captured when the item was added — a later catalogue change does not re-price it."
        actions={<StatusPill status={checkout.status} family="checkout" />}
      />

      <div className="grid gap-4 lg:grid-cols-[1fr_18rem]">
        <Card>
          <CardContent className="overflow-x-auto p-0">
            <table className="w-full text-left text-label-sm">
              <thead>
                <tr className="border-b border-border-subtle text-caption uppercase text-fg-subtle">
                  {['Item', 'Qty', 'Unit', 'Line total'].map((h) => (
                    <th key={h} className="px-4 py-2.5 font-[510]">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {checkout.lines.map((line) => (
                  <tr key={line.productId} className="border-b border-border-subtle last:border-0">
                    <td className="px-4 py-3">
                      <Link
                        href={`/agentic/catalog/${line.productId}`}
                        className="text-accent-text hover:underline"
                      >
                        {line.name}
                      </Link>
                      <p className="font-mono text-caption text-fg-subtle">{line.sku}</p>
                    </td>
                    <td className="px-4 py-3 font-mono text-fg-muted">{line.quantity}</td>
                    <td className="px-4 py-3 font-mono text-fg-muted">
                      {formatMoney(line.unitPriceMinor, checkout.currency)}
                    </td>
                    <td className="px-4 py-3 font-mono text-fg-muted">
                      {formatMoney(line.lineTotalMinor, checkout.currency)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardContent>
        </Card>

        <Card className="h-fit">
          <CardContent className="pt-4">
            <DetailList
              rows={[
                { label: 'Id', value: checkout.id, mono: true },
                {
                  label: 'Subtotal',
                  value: formatMoney(checkout.subtotalMinor, checkout.currency),
                  mono: true,
                },
                {
                  label: 'Discount',
                  value: formatMoney(checkout.discountMinor, checkout.currency),
                  mono: true,
                },
                {
                  label: 'Total',
                  value: formatMoney(checkout.totalMinor, checkout.currency),
                  mono: true,
                },
                {
                  label: 'Payment',
                  value: checkout.paymentId ? (
                    <Link
                      href={`/payments/${checkout.paymentId}`}
                      className="text-accent-text hover:underline"
                    >
                      {checkout.paymentId}
                    </Link>
                  ) : (
                    '—'
                  ),
                  mono: true,
                },
                { label: 'Expires', value: formatDateTime(checkout.expiresAt), mono: true },
              ]}
            />
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
