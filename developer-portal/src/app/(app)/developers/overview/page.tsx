import { Activity, FlaskConical, KeyRound, ScrollText, Sparkles, Webhook } from 'lucide-react';
import type { Metadata } from 'next';
import Link from 'next/link';

import { IntegrationStatus } from '@/components/integration/integration-status';
import { NextStepCard } from '@/components/integration/next-step-card';
import { PageHeader } from '@/components/patterns/page-header';
import { Card, CardContent } from '@/components/ui/card';
import type { PaymentResponse } from '@/generated/models';
import { callAs } from '@/lib/api/client';
import { isLive, keyStatus } from '@/lib/api-keys/status';
import { listApiKeys } from '@/lib/platform/api-keys';
import { requireMerchant } from '@/lib/session/require';

export const metadata: Metadata = { title: 'Integration' };

/**
 * The Integration hub (integration UX pass).
 *
 * First question answered: "how connected am I?" — from real data (`listApiKeys`,
 * `listPayments`), never fabricated. Then one recommended next step, then the tools.
 */
export const dynamic = 'force-dynamic';

export default async function IntegrationHubPage() {
  const session = await requireMerchant();

  const [listing, payments] = await Promise.all([
    listApiKeys(session.accessToken).catch(() => ({ status: 'unavailable' as const })),
    callAs<{ data?: PaymentResponse[] }>('listPayments', {
      session,
      query: { limit: 20 },
    }).catch(() => ({ data: [] as PaymentResponse[] })),
  ]);

  const now = new Date();
  const keysHere =
    listing.status === 'found'
      ? listing.keys.filter((k) => k.mode === session.mode && k.type === 'SECRET')
      : [];
  const usableKeys = keysHere.filter((k) => isLive(keyStatus(k, now)));
  const paymentRows = payments.data ?? [];

  const hasKey = usableKeys.length > 0;
  const sent = paymentRows.length > 0;

  const nextStep = !hasKey
    ? {
        title: 'Create a test key',
        body: 'A secret key your server uses to call the API. Shown once, at creation.',
        href: '/developers/api-keys',
        cta: 'Go to API keys',
      }
    : !sent
      ? {
          title: 'Run a test payment',
          body: 'Follow the quickstart to create, authorize and capture a payment — about 5 minutes.',
          href: '/developers/quickstart',
          cta: 'Open the quickstart',
        }
      : {
          title: 'Set up webhooks',
          body: 'Be notified of events on your server instead of polling. Deliveries are signed.',
          href: '/developers/webhooks',
          cta: 'Configure webhooks',
        };

  return (
    <div>
      <PageHeader
        title="Integration"
        description="Connect PaymentFlow to your application — by hand with the quickstart, or by letting a coding agent do it."
      />

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
        <IntegrationStatus
          mode={session.mode}
          hasKey={hasKey}
          keyCount={usableKeys.length}
          paymentCount={paymentRows.length}
          {...(paymentRows[0]?.createdAt ? { lastPaymentAt: paymentRows[0].createdAt } : {})}
        />
        <NextStepCard
          title={nextStep.title}
          body={nextStep.body}
          href={nextStep.href}
          cta={nextStep.cta}
        />
      </div>

      <h2 className="mt-10 mb-3 text-label font-[510] text-fg">Ways to integrate</h2>
      <div className="grid gap-4 sm:grid-cols-2">
        <Link href="/developers/quickstart">
          <Card interactive className="h-full">
            <CardContent className="pt-5">
              <KeyRound aria-hidden className="size-4 text-fg-subtle" />
              <p className="mt-3 text-label font-[510] text-fg">Quickstart</p>
              <p className="mt-1.5 text-label text-fg-subtle">
                Copy-paste steps for your language: key → payment → authorize → capture → verify.
              </p>
            </CardContent>
          </Card>
        </Link>
        <Link href="/developers/ai">
          <Card interactive className="h-full">
            <CardContent className="pt-5">
              <Sparkles aria-hidden className="size-4 text-accent-text" />
              <p className="mt-3 text-label font-[510] text-fg">AI integration</p>
              <p className="mt-1.5 text-label text-fg-subtle">
                Generate a prompt for Claude Code, Codex or Cursor and let it do the integration.
              </p>
            </CardContent>
          </Card>
        </Link>
      </div>

      <h2 className="mt-10 mb-3 text-label font-[510] text-fg">Tools &amp; reference</h2>
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {[
          {
            href: '/developers/api-keys',
            label: 'API keys',
            body: 'Create, reveal once, rotate, revoke.',
            icon: KeyRound,
          },
          {
            href: '/developers/webhooks',
            label: 'Webhooks',
            body: 'Endpoints, deliveries, replay, secret rotation.',
            icon: Webhook,
          },
          {
            href: '/developers/events',
            label: 'Events',
            body: 'The feed you reconcile webhooks against.',
            icon: Activity,
          },
          {
            href: '/developers/logs',
            label: 'Request logs',
            body: 'Every call your keys made, by request id.',
            icon: ScrollText,
          },
          {
            href: '/developers/sandbox',
            label: 'Sandbox',
            body: 'Test instruments and forced outcomes.',
            icon: FlaskConical,
          },
          {
            href: '/developers/sdks',
            label: 'SDKs',
            body: 'TypeScript and Python, generated from the contract.',
            icon: KeyRound,
          },
        ].map(({ href, label, body, icon: Icon }) => (
          <Link key={href} href={href}>
            <Card interactive className="h-full">
              <CardContent className="pt-5">
                <Icon aria-hidden className="size-4 text-fg-subtle" />
                <p className="mt-3 text-label font-[510] text-fg">{label}</p>
                <p className="mt-1.5 text-label text-fg-subtle">{body}</p>
              </CardContent>
            </Card>
          </Link>
        ))}
      </div>
    </div>
  );
}
