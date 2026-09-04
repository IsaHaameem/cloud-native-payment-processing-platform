import type { Metadata } from 'next';
import Link from 'next/link';

import { EmptyState } from '@/components/patterns/empty-state';
import { ErrorState } from '@/components/patterns/error-state';
import { PageHeader } from '@/components/patterns/page-header';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { loadAgentic } from '@/lib/agentic/load';
import { listProducts } from '@/lib/agentic/operations';
import { formatMoney } from '@/lib/format';
import { requireMerchant } from '@/lib/session/require';

export const metadata: Metadata = { title: 'Catalog' };

export const dynamic = 'force-dynamic';

/**
 * The catalogue the agent can discover and sell from — live (M-agentic, G-2).
 *
 * `GET /api/agentic/catalog/products` through the portal's signed proxy. Prices are integer minor
 * units; availability the agent sees is a boolean, never a stock count — the projection the
 * service returns is deliberately narrower than the row. Read-only: curating the catalogue is a
 * feature in its own right and is still a documented gap.
 */
export default async function CatalogPage({
  searchParams,
}: {
  searchParams: Promise<{ q?: string }>;
}) {
  const session = await requireMerchant();
  const { q } = await searchParams;
  const result = await loadAgentic(() => listProducts(session, { query: q, limit: 60 }));

  return (
    <div>
      <PageHeader
        title="Catalog"
        description="The products your agent can discover and sell from. Prices in integer minor units; availability is a boolean the agent sees, never a count."
      />

      {result.error ? (
        <ErrorState
          title="The catalogue could not be loaded"
          description={result.error.message}
          code={result.error.code}
          requestId={result.error.requestId}
        />
      ) : result.data.data.length === 0 ? (
        <EmptyState
          title={q ? 'No products match that search' : 'No products yet'}
          description={
            q
              ? 'Try a different term, or clear the search to see the whole catalogue.'
              : 'The demo catalogue is seeded per merchant on first start. This merchant has none.'
          }
        />
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {result.data.data.map((product) => (
            <Link key={product.id} href={`/agentic/catalog/${product.id}`}>
              <Card className="h-full transition-colors hover:bg-surface-hover">
                <CardContent className="space-y-2 pt-4">
                  <div className="flex items-start justify-between gap-2">
                    <span className="text-label font-[510] text-fg">{product.name}</span>
                    <Badge tone={product.available ? 'success' : 'neutral'}>
                      {product.available ? 'in stock' : 'unavailable'}
                    </Badge>
                  </div>
                  <p className="font-mono text-label-sm text-fg-muted">
                    {formatMoney(product.priceMinor, product.currency)}
                  </p>
                  <p className="text-caption text-fg-subtle">
                    {product.category ?? '—'} · <span className="font-mono">{product.sku}</span>
                  </p>
                  {product.description ? (
                    <p className="line-clamp-2 text-label-sm text-fg-subtle">
                      {product.description}
                    </p>
                  ) : null}
                </CardContent>
              </Card>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
