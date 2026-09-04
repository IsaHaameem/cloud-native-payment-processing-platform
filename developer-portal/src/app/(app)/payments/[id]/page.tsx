import type { Metadata } from 'next';

import { readCsrfToken } from '@/lib/security/csrf';
import { requireMerchant } from '@/lib/session/require';

import { PaymentDetail } from './payment-detail';

export const metadata: Metadata = { title: 'Payment' };

/**
 * A single payment (frontend build).
 *
 * `frontend_Design.md §13.2` calls this "the strongest screen in the product": the place the
 * platform's chain — payment → FSM transition → ledger posting → event → webhook → audit — is
 * made navigable. The rows themselves are fetched by the client (`usePlatformObject`) so a
 * mutation can refetch on the same terms; this server component proves the session and merchant
 * before anything is requested, and mints the CSRF token the FSM actions need.
 */
export const dynamic = 'force-dynamic';

export default async function PaymentDetailPage({ params }: { params: Promise<{ id: string }> }) {
  await requireMerchant();
  const { id } = await params;
  const csrfToken = await readCsrfToken();

  return <PaymentDetail id={id} csrfToken={csrfToken} />;
}
