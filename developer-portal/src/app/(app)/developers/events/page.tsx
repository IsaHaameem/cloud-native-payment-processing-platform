import type { Metadata } from 'next';

import { PageHeader } from '@/components/patterns/page-header';
import { requireMerchant } from '@/lib/session/require';

import { EventsBrowser } from './events-browser';

export const metadata: Metadata = { title: 'Events' };

/**
 * The events feed (frontend build).
 *
 * `frontend_Design.md §24`: `GET /v1/events`, `GET /v1/events/{id}`. An event id is byte-identical
 * to the id in the webhook body for the same event, so a delivery reconciles here without storing
 * anything. Ordered by `created` — a redelivery cannot reorder the feed.
 */
export const dynamic = 'force-dynamic';

export default async function EventsPage() {
  await requireMerchant();
  return (
    <div>
      <PageHeader
        title="Events"
        description="Every merchant-facing event, newest first. The payload here is byte-identical to what your webhook endpoint received."
      />
      <EventsBrowser />
    </div>
  );
}
