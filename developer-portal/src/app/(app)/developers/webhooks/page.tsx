import type { Metadata } from 'next';

import { PageHeader } from '@/components/patterns/page-header';
import { readCsrfToken } from '@/lib/security/csrf';
import { requireMerchant } from '@/lib/session/require';

import { WebhooksClient } from './webhooks-client';

export const metadata: Metadata = { title: 'Webhooks' };

/**
 * Webhook endpoints and deliveries (frontend build).
 *
 * `frontend_Design.md §23`, scope `webhooks:manage`. Endpoints and deliveries are read live; the
 * signing secret is shown exactly once at creation or rotation, and the endpoint URL is not
 * editable because the API refuses it — the URL is half an endpoint's identity.
 */
export const dynamic = 'force-dynamic';

export default async function WebhooksPage() {
  await requireMerchant();
  const csrfToken = await readCsrfToken();
  return (
    <div>
      <PageHeader
        title="Webhooks"
        description="Signed deliveries with attempt history and replay. The signature covers the exact bytes sent — verify before you parse."
      />
      <WebhooksClient csrfToken={csrfToken} />
    </div>
  );
}
