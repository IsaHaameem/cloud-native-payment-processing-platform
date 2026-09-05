import Link from 'next/link';
import type { Metadata } from 'next';

import { MarketingHero } from '@/components/marketing/marketing-hero';
import { MarketingSection } from '@/components/marketing/marketing-section';
import { Reveal, RevealItem } from '@/components/marketing/reveal';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';

export const metadata: Metadata = {
  title: 'Pricing',
  description:
    'Sandbox access is open and unmetered. Production terms depend on your volume, currencies and provider mix, so we quote them rather than publish them.',
};

/** Reference: `Pricing.dc.html`. */

const PLANS = [
  {
    tag: 'Sandbox',
    badge: 'Free, no expiry',
    badgeTone: 'warning' as const,
    price: 'Free',
    priceNote: 'test mode only',
    body: 'The whole platform with test credentials. Build and verify an integration end to end before anyone quotes you anything.',
    items: [
      'Every REST endpoint and both SDKs',
      '17 test instruments with reproducible outcomes',
      'Simulation overrides for declines and latency',
      'Full agentic runtime, policy engine and approvals',
      'Webhooks, events and request logs',
    ],
    cta: { label: 'Start in test mode', href: '/signup', variant: 'primary' as const },
    featured: false,
  },
  {
    tag: 'Growth',
    badge: 'Quoted',
    badgeTone: 'accent' as const,
    price: 'Talk to us',
    priceNote: 'platform fee on live volume',
    body: 'Live-mode credentials and the production-shaped orchestration, ledger and delivery guarantees. The current platform settles against a simulated acquirer — no real funds move in either mode.',
    items: [
      'Everything in Sandbox, in live mode',
      'Live API keys with scoped permissions',
      'Signed webhooks with replay and auto-disable',
      'Double-entry ledger and balance projection',
      'Analytics and usage reporting',
    ],
    cta: { label: 'Contact sales', href: '/contact', variant: 'primary' as const },
    featured: true,
  },
  {
    tag: 'Enterprise',
    badge: undefined,
    badgeTone: 'neutral' as const,
    price: 'Talk to us',
    priceNote: 'commercial terms by agreement',
    body: 'For teams running PaymentFlow as core payment infrastructure, with requirements around residency, isolation and support.',
    items: [
      'Everything in Growth',
      'Deployment and data-residency options',
      'API version pinning commitments',
      'Named support and escalation path',
      'Security and architecture review',
    ],
    cta: { label: 'Talk to the team', href: '/contact', variant: 'secondary' as const },
    featured: false,
  },
];

const ROWS: [string, string, string, string][] = [
  ['Payment state machine and idempotency', 'included', 'included', 'included'],
  ['Double-entry ledger and balance', 'included', 'included', 'included'],
  ['Signed webhooks, replay, auto-disable', 'included', 'included', 'included'],
  ['Events feed and request logs', 'included', 'included', 'included'],
  ['Agentic runtime, policy engine, approvals', 'included', 'included', 'included'],
  ['Test instruments and simulation overrides', 'included', 'test only', 'test only'],
  ['Live mode credentials', '—', 'included', 'included'],
  ['Analytics and usage', 'included', 'included', 'included'],
  ['Data residency options', '—', '—', 'by agreement'],
  ['Named support and escalation', '—', 'standard', 'named'],
];

function Cell({ value }: { value: string }) {
  const cls =
    value === '—' ? 'text-fg-faint' : value === 'included' ? 'text-success' : 'text-fg-muted';
  return <span className={`font-mono text-label-sm ${cls}`}>{value}</span>;
}

export default function PricingPage() {
  return (
    <>
      <MarketingHero
        eyebrow="Pricing"
        title="Start in test mode. Talk to us before you go live."
        lede="Sandbox access is open and unmetered — every endpoint, every test instrument, the full agentic runtime. Production terms depend on your volume, currencies and provider mix, so we quote them rather than publish them."
      />

      <MarketingSection eyebrow="Plans" title="Three tiers">
        <Reveal stagger className="grid gap-4 grid-cols-1 lg:grid-cols-3">
          {PLANS.map((p) => (
            <RevealItem key={p.tag}>
              <Card
                className={`flex h-full flex-col ${p.featured ? 'ring-1 ring-accent-ring' : ''}`}
              >
                <CardContent className="flex flex-1 flex-col pt-5">
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-mono text-caption tracking-[0.1em] text-fg-subtle uppercase">
                      {p.tag}
                    </span>
                    {p.badge ? <Badge tone={p.badgeTone}>{p.badge}</Badge> : null}
                  </div>
                  <p className="mt-3.5 text-[1.75rem] leading-tight font-[510] text-fg">
                    {p.price}
                  </p>
                  <p className="mt-1 text-label text-fg-subtle">{p.priceNote}</p>
                  <p className="mt-4 text-label text-fg-muted">{p.body}</p>
                  <ul className="mt-4 space-y-2.5 border-t border-border-subtle pt-4">
                    {p.items.map((item) => (
                      <li key={item} className="flex gap-2 text-label text-fg-subtle">
                        <span
                          aria-hidden
                          className="mt-2 size-1.5 shrink-0 rounded-full bg-success"
                        />
                        {item}
                      </li>
                    ))}
                  </ul>
                  <div className="flex-1" />
                  <Button variant={p.cta.variant} size="lg" asChild className="mt-6 w-full">
                    <Link href={p.cta.href}>{p.cta.label}</Link>
                  </Button>
                </CardContent>
              </Card>
            </RevealItem>
          ))}
        </Reveal>
      </MarketingSection>

      <MarketingSection
        eyebrow="Comparison"
        title="What each tier includes"
        lede="Capabilities that exist in the platform today."
      >
        <Card>
          <CardContent className="overflow-x-auto p-0">
            <table className="w-full min-w-[36rem] border-collapse text-left">
              <thead>
                <tr className="border-b border-border-subtle">
                  <th className="px-4 py-2.5 text-caption font-[510] tracking-[0.04em] text-fg-subtle uppercase">
                    Capability
                  </th>
                  {['Sandbox', 'Growth', 'Enterprise'].map((h) => (
                    <th
                      key={h}
                      className="px-4 py-2.5 text-center text-caption font-[510] tracking-[0.04em] text-fg-subtle uppercase"
                    >
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {ROWS.map(([name, a, b, c]) => (
                  <tr key={name} className="border-b border-border-subtle last:border-0">
                    <td className="px-4 py-3 text-label text-fg-muted">{name}</td>
                    <td className="px-4 py-3 text-center">
                      <Cell value={a} />
                    </td>
                    <td className="px-4 py-3 text-center">
                      <Cell value={b} />
                    </td>
                    <td className="px-4 py-3 text-center">
                      <Cell value={c} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardContent>
        </Card>
      </MarketingSection>

      <MarketingSection eyebrow="Why" title="No published rates" bordered={false}>
        <div className="grid gap-6 grid-cols-1 md:grid-cols-2">
          <Card>
            <CardContent className="pt-5">
              <h3 className="text-body-lg font-[510] text-fg">Why there are no published rates</h3>
              <p className="mt-2 text-label text-fg-subtle">
                PaymentFlow orchestrates your provider relationships rather than replacing them.
                Your cost of acceptance stays with your acquirer; what we quote is the platform on
                top. Publishing a single blended number would misrepresent both.
              </p>
            </CardContent>
          </Card>
          <div className="rounded-lg border border-mode-test-border bg-mode-test-surface p-5">
            <h3 className="text-body-lg font-[510] text-mode-test">Sandbox is not a trial</h3>
            <p className="mt-2 text-label text-fg-muted">
              It does not expire and it is not feature-gated. It is the same platform with test
              credentials — 17 reproducible test instruments, simulation overrides, and provider
              decisions that are never labelled as real authorizations.
            </p>
          </div>
        </div>
      </MarketingSection>
    </>
  );
}
