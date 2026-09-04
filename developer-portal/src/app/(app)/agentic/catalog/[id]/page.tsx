import type { Metadata } from 'next';
import Link from 'next/link';

import { DetailList } from '@/components/patterns/detail-list';
import { ErrorState } from '@/components/patterns/error-state';
import { PageHeader } from '@/components/patterns/page-header';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { loadAgentic } from '@/lib/agentic/load';
import { getProduct } from '@/lib/agentic/operations';
import { formatMoney } from '@/lib/format';
import { requireMerchant } from '@/lib/session/require';

export const metadata: Metadata = { title: 'Product' };

export const dynamic = 'force-dynamic';

/** One catalogue product — live (M-agentic, G-2). */
export default async function ProductDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const session = await requireMerchant();
  const { id } = await params;
  const result = await loadAgentic(() => getProduct(session, id));

  if (result.error) {
    return (
      <div>
        <PageHeader title="Product" description={`Product ${id}.`} />
        <ErrorState
          title="This product could not be loaded"
          description={result.error.message}
          code={result.error.code}
          requestId={result.error.requestId}
        />
      </div>
    );
  }

  const product = result.data;

  return (
    <div>
      <PageHeader
        title={product.name}
        description="What the agent is shown about this product. It can quote this; it cannot change the price."
        actions={
          <Badge tone={product.available ? 'success' : 'neutral'}>
            {product.available ? 'in stock' : 'unavailable'}
          </Badge>
        }
      />
      <Card>
        <CardContent className="pt-4">
          <DetailList
            rows={[
              { label: 'SKU', value: product.sku, mono: true },
              { label: 'Category', value: product.category ?? '—' },
              {
                label: 'Price',
                value: formatMoney(product.priceMinor, product.currency),
                mono: true,
              },
              { label: 'Price (minor units)', value: String(product.priceMinor), mono: true },
              { label: 'Currency', value: product.currency, mono: true },
              { label: 'Description', value: product.description ?? '—' },
            ]}
          />
        </CardContent>
      </Card>
      <p className="mt-4 text-label-sm">
        <Link href="/agentic/catalog" className="text-accent-text hover:underline">
          ← All products
        </Link>
      </p>
    </div>
  );
}
