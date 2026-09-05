import type { Metadata } from 'next';

import { PageHeader } from '@/components/patterns/page-header';
import { requireMerchant } from '@/lib/session/require';

import { BalanceView } from './balance-view';

export const metadata: Metadata = { title: 'Balance' };

/**
 * Balance and balance transactions (frontend build).
 *
 * `frontend_Design.md §14`: `transaction-service` exposes exactly two public endpoints —
 * `GET /v1/balance` and `GET /v1/balance_transactions`. There is no public double-entry journal
 * view; `/v1/balance_transactions` is the merchant-facing projection, and that is what this
 * screen shows.
 */
export const dynamic = 'force-dynamic';

export default async function BalancePage() {
  await requireMerchant();
  return (
    <div>
      <PageHeader
        title="Balance"
        description="Money you hold, per currency, and the ledger postings behind it. Available is captured and owed to you; pending is authorized but not yet captured."
      />
      <BalanceView />
    </div>
  );
}
