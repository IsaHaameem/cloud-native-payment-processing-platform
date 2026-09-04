import type { Metadata } from 'next';

import { PageHeader } from '@/components/patterns/page-header';
import { readCsrfToken } from '@/lib/security/csrf';
import { requireMerchant } from '@/lib/session/require';

import { SandboxClient } from './sandbox-client';

export const metadata: Metadata = { title: 'Sandbox' };

/**
 * The sandbox (frontend build).
 *
 * `/v1/test/**`: 17 test instruments with fixed outcomes, a live simulation override, and a
 * per-payment decision log. A demonstration provider decision is never presented as a
 * cardholder authorization.
 */
export const dynamic = 'force-dynamic';

export default async function SandboxPage() {
  await requireMerchant();
  const csrfToken = await readCsrfToken();
  return (
    <div>
      <PageHeader
        title="Sandbox"
        description="Reproducible failures, not a happy path. Test instruments, a simulation override that applies to the next N payments, and the decision log behind each one. The sandboxed acquirer decides every payment in this deployment — test and live alike — so no real money moves."
      />
      <SandboxClient csrfToken={csrfToken} />
    </div>
  );
}
