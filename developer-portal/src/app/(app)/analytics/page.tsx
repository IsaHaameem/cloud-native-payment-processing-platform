import type { Metadata } from 'next';

import { PageHeader } from '@/components/patterns/page-header';
import { requireMerchant } from '@/lib/session/require';

import { AnalyticsClient } from './analytics-client';

export const metadata: Metadata = { title: 'Analytics' };

/**
 * Analytics (frontend build).
 *
 * `frontend_Design.md §25`: two tabs, and only two, because that is what exists — `GET
 * /v1/analytics/payments` (params `from`, `to` only) and `GET /v1/usage`. Average order value,
 * MRR, churn and customer counts are not derivable and are not invented (§38 G-8).
 */
export const dynamic = 'force-dynamic';

export default async function AnalyticsPage() {
  await requireMerchant();
  return (
    <div>
      <PageHeader
        title="Analytics"
        description="Payment outcomes and API usage over a window you choose. Every figure is one the platform computes — nothing here is estimated."
      />
      <AnalyticsClient />
    </div>
  );
}
