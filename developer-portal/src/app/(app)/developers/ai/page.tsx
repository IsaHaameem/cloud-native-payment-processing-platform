import type { Metadata } from 'next';

import { PromptGenerator } from '@/components/integration/prompt-generator';
import { NextStepCard } from '@/components/integration/next-step-card';
import { PageHeader } from '@/components/patterns/page-header';
import { requireMerchant } from '@/lib/session/require';

export const metadata: Metadata = { title: 'AI integration' };

/**
 * "Let your coding agent integrate PaymentFlow" (integration UX pass).
 *
 * Three picks → a prompt the merchant pastes into Claude Code / Codex / Cursor. The prompt is
 * generated client-side from the frozen contract (`@/lib/integration/prompt`); it names only
 * real endpoints and carries no secret. Mode comes from the session so the prompt targets the
 * right key prefix.
 */
export const dynamic = 'force-dynamic';

export default async function AiIntegrationPage() {
  const session = await requireMerchant();

  return (
    <div className="pb-4">
      <PageHeader
        title="Let your coding agent integrate PaymentFlow"
        description="Copy the generated prompt into Claude Code, Codex, Cursor, or your preferred coding agent. It will inspect your project and implement the integration using PaymentFlow’s API — you don’t need to learn the whole API first."
      />

      <PromptGenerator mode={session.mode} />

      <div className="mt-8 grid gap-3 sm:grid-cols-2">
        <NextStepCard
          title="Prefer to do it by hand?"
          body="The quickstart walks the same integration step by step, with copy-paste code for your stack."
          href="/developers/quickstart"
          cta="Open the quickstart"
        />
        <NextStepCard
          title="Need a key first?"
          body="Your agent needs a test key in your server environment. Create one and copy the .env line."
          href="/developers/api-keys"
          cta="Manage API keys"
        />
      </div>
    </div>
  );
}
