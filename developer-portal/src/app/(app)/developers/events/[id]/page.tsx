import type { Metadata } from 'next';

import { requireMerchant } from '@/lib/session/require';

import { EventDetail } from './event-detail';

export const metadata: Metadata = { title: 'Event' };

export const dynamic = 'force-dynamic';

export default async function EventDetailPage({ params }: { params: Promise<{ id: string }> }) {
  await requireMerchant();
  const { id } = await params;
  return <EventDetail id={id} />;
}
