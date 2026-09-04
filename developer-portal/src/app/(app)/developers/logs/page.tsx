import type { Metadata } from 'next';

import { PageHeader } from '@/components/patterns/page-header';
import { requireMerchant } from '@/lib/session/require';

import { LogsBrowser } from './logs-browser';

export const metadata: Metadata = { title: 'Request logs' };

/**
 * The API request log (frontend build).
 *
 * `GET /v1/request_logs`, scope `logs:read`. Keyed by `requestId` — the identifier the platform's
 * error contract tells a merchant to quote in a support request.
 */
export const dynamic = 'force-dynamic';

export default async function LogsPage() {
  await requireMerchant();
  return (
    <div>
      <PageHeader
        title="Request logs"
        description="Every API call your keys made, newest first, with the request id support will ask for."
      />
      <LogsBrowser />
    </div>
  );
}
