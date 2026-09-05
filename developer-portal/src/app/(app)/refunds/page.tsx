import type { Metadata } from 'next';

import { PageHeader } from '@/components/patterns/page-header';
import { requireMerchant } from '@/lib/session/require';

import { RefundsBrowser } from './refunds-browser';

export const metadata: Metadata = { title: 'Refunds' };

/**
 * The refunds list (frontend build).
 *
 * `frontend_Design.md §13.3`: `/v1/refunds` is a first-class listing endpoint with its own
 * filters. A refund is created from a payment, never here — the API has no other path — so this
 * list has no "New refund" action.
 */
export const dynamic = 'force-dynamic';

export default async function RefundsPage() {
  await requireMerchant();
  return (
    <div>
      <PageHeader
        title="Refunds"
        description="Every refund on your account, newest first. A refund is issued from its payment — there is no way to create one here."
      />
      <RefundsBrowser />
    </div>
  );
}
