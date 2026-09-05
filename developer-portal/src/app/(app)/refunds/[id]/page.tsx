import type { Metadata } from 'next';

import { requireMerchant } from '@/lib/session/require';

import { RefundDetail } from './refund-detail';

export const metadata: Metadata = { title: 'Refund' };

export const dynamic = 'force-dynamic';

export default async function RefundDetailPage({ params }: { params: Promise<{ id: string }> }) {
  await requireMerchant();
  const { id } = await params;
  return <RefundDetail id={id} />;
}
