import type { Metadata } from 'next';

import { PageHeader } from '@/components/patterns/page-header';
import { currentMerchant } from '@/lib/platform/current-merchant';
import { requireMerchant } from '@/lib/session/require';

import { OverviewClient } from './overview-client';

export const metadata: Metadata = { title: 'Overview' };

/**
 * The authenticated entry point (frontend build).
 *
 * `frontend_Design.md §12`: today's captured volume, success rate, payments created, failed and
 * refunded — every figure from `GET /v1/analytics/payments`, which the read proxy now exposes —
 * plus recent payments, recent failures, and operational alerts derived from webhook endpoint
 * health. Nothing on this page is fabricated: an account with no activity gets the getting-started
 * checklist instead of a wall of zeros.
 *
 * `requireMerchant` closes the entry flow — a user who registered a minute ago has a session and
 * no merchant, and this is the redirect that routes them to `/onboarding`.
 */
export const dynamic = 'force-dynamic';

export default async function DashboardPage() {
  const session = await requireMerchant();
  const lookup = await currentMerchant(session.accessToken);
  const businessName = lookup.status === 'found' ? lookup.merchant.businessName : undefined;

  return (
    <div>
      <PageHeader
        title="Overview"
        description={
          businessName
            ? `${businessName} · ${session.mode} mode`
            : `Your ${session.mode}-mode activity at a glance`
        }
      />
      <OverviewClient mode={session.mode} />
    </div>
  );
}
