import type { Metadata } from 'next';

import { NextStepCard } from '@/components/integration/next-step-card';
import { PageHeader } from '@/components/patterns/page-header';
import { DEFAULT_BASE_URL } from '@/generated/contract';
import { requireMerchant } from '@/lib/session/require';

import { QuickstartClient } from './quickstart-client';

export const metadata: Metadata = { title: 'Quickstart' };

/**
 * "Get your first PaymentFlow payment running" (integration UX pass).
 *
 * Six steps, ~5 minutes, one language chosen at the top. Every command is real — SDK methods
 * from the published SDKs, raw calls from the frozen contract. Advanced material is collapsed.
 */
export const dynamic = 'force-dynamic';

export default async function QuickstartPage() {
  const session = await requireMerchant();

  return (
    <div className="pb-4">
      <PageHeader
        title="Get your first PaymentFlow payment running"
        description="About 5 minutes. Create a key, put it on your server, create a payment, authorize and capture it, and see it in the dashboard."
      />

      <QuickstartClient mode={session.mode} baseUrl={DEFAULT_BASE_URL} />

      <div className="mt-10 grid gap-3 sm:grid-cols-2">
        <NextStepCard
          title="Let an AI agent do it"
          body="Generate a prompt for Claude Code, Codex or Cursor and have it implement the integration in your project."
          href="/developers/ai"
          cta="Open AI integration"
        />
        <NextStepCard
          title="It's working — what next?"
          body="Point a webhook endpoint at your server so you're notified of events instead of polling."
          href="/developers/webhooks"
          cta="Set up webhooks"
        />
      </div>
    </div>
  );
}
