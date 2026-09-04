import Link from 'next/link';
import type { Metadata } from 'next';

import { MarketingHero } from '@/components/marketing/marketing-hero';
import { MarketingSection } from '@/components/marketing/marketing-section';
import { Reveal, RevealItem } from '@/components/marketing/reveal';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { RiskBadge, type RiskClass } from '@/components/ui/risk-badge';

export const metadata: Metadata = {
  title: 'Agentic Commerce',
  description:
    'A detachable extension that makes your catalogue transactable by AI buyers: an agent runtime, a typed tool registry, a deterministic policy engine, a human approval workflow and an action log. The agent proposes. The platform decides.',
};

/** Reference: `Agentic Commerce.dc.html`. The committed values are `frontend_Design.md §18.1`. */

const CHAIN = [
  { step: 'Agent', tone: 'accent' as const, note: 'Proposes a tool call with typed arguments.' },
  {
    step: 'Validation',
    tone: 'success' as const,
    note: 'Arguments checked against the tool schema.',
  },
  { step: 'Policy', tone: 'warning' as const, note: '17 deterministic rules, one outcome each.' },
  { step: 'Approval', tone: 'warning' as const, note: 'A person decides, inside a bound window.' },
  {
    step: 'Checkout',
    tone: 'neutral' as const,
    note: 'Total derived from the lines, then locked.',
  },
  {
    step: 'Payment',
    tone: 'success' as const,
    note: 'The platform executes, with an idempotency key.',
  },
  { step: 'Action log', tone: 'neutral' as const, note: 'Every step recorded with its ids.' },
];

const TOOLS: { name: string; risk: RiskClass; note: string }[] = [
  { name: 'catalogue lookup', risk: 'READ', note: 'Cannot move money.' },
  { name: 'checkout operations', risk: 'COMMERCE', note: 'Builds a quote; no charge.' },
  { name: 'payment execution', risk: 'PAYMENT', note: 'Moves money; capped and logged.' },
  { name: 'refund creation', risk: 'REFUND', note: 'Moves money out; approval above ₹1,000.' },
];

const LIMITS = [
  { rule: 'payment-amount-cap', limit: '₹50,000', outcome: 'REFUSE', tone: 'danger' as const },
  {
    rule: 'conversation-spend-budget',
    limit: '₹1,00,000',
    outcome: 'REFUSE',
    tone: 'danger' as const,
  },
  {
    rule: 'refund-approval-threshold',
    limit: '₹1,000',
    outcome: 'REQUIRES_APPROVAL',
    tone: 'warning' as const,
  },
  { rule: 'refund-amount-cap', limit: '₹20,000', outcome: 'REFUSE', tone: 'danger' as const },
  {
    rule: 'conversation-refund-budget',
    limit: '₹50,000',
    outcome: 'REFUSE',
    tone: 'danger' as const,
  },
  { rule: 'tool-call-ceiling', limit: '60 calls', outcome: 'REFUSE', tone: 'danger' as const },
  {
    rule: 'approval TTL',
    limit: '30 minutes',
    outcome: 'expires unredeemed',
    tone: 'neutral' as const,
  },
];

const NOTS = [
  {
    title: 'Not a buyer-facing chat',
    body: 'The agent’s conversation surface belongs to your application. In this dashboard, conversations are a read-only observability record — you never chat with the agent here.',
  },
  {
    title: 'Not a customer database',
    body: 'There is no customer entity in the platform, so nothing here pretends to be one. Payment metadata is filterable, which gives an honest per-customer history when you populate it.',
  },
  {
    title: 'Not an autonomous spender',
    body: 'The agent holds no credential and executes nothing itself. It proposes; the platform validates, decides, and — above the threshold — waits for a person.',
  },
];

export default function AgenticCommercePage() {
  return (
    <>
      <MarketingHero
        eyebrow="Agentic Commerce"
        title="Let an agent transact without handing it your money."
        lede="A detachable extension that makes your catalogue transactable by AI buyers: an agent runtime, a typed tool registry, a deterministic policy engine, a human approval workflow and an action log. The agent proposes. The platform decides."
        actions={[
          { label: 'Start in test mode', href: '/signup', variant: 'primary' },
          { label: 'See the platform', href: '/platform' },
        ]}
      />

      <MarketingSection eyebrow="The chain" title="Nothing executes before the chain completes">
        <Reveal stagger className="grid gap-3 grid-cols-1 sm:grid-cols-2 lg:grid-cols-4">
          {CHAIN.map((c) => (
            <RevealItem key={c.step}>
              <Card className="h-full">
                <CardContent className="space-y-2 pt-5">
                  <Badge tone={c.tone} dot>
                    {c.step}
                  </Badge>
                  <p className="text-label text-fg-subtle">{c.note}</p>
                </CardContent>
              </Card>
            </RevealItem>
          ))}
        </Reveal>
      </MarketingSection>

      <MarketingSection
        eyebrow="Tool registry"
        title="Every capability the agent has, and its risk class"
        lede="Seven tools are registered. The inventory is read from server configuration, so the dashboard lists it rather than a marketing page inventing it. Risk class is what governs which rules apply."
      >
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-[1.4fr_1fr]">
          <Card>
            <CardContent className="divide-y divide-border-subtle pt-2 pb-2">
              {TOOLS.map((t) => (
                <div key={t.name} className="flex flex-wrap items-center gap-3 py-3">
                  <span className="min-w-0 flex-1 font-mono text-label text-fg-muted">
                    {t.name}
                  </span>
                  <RiskBadge risk={t.risk} />
                  <span className="min-w-0 flex-1 text-label text-fg-subtle">{t.note}</span>
                </div>
              ))}
            </CardContent>
          </Card>
          <div className="space-y-4">
            <Card>
              <CardContent className="pt-5">
                <h3 className="text-body-lg font-[510] text-fg">
                  Deterministic, not discretionary
                </h3>
                <p className="mt-2 text-label text-fg-subtle">
                  Seventeen rules, each with exactly one outcome. Structural checks first, then
                  money preconditions, then hard caps, then the approval threshold, then a default
                  permit. Same input, same decision, every time.
                </p>
                <p className="mt-3 font-mono text-label-sm text-fg-subtle">
                  policy version 2026-08-20.1
                </p>
              </CardContent>
            </Card>
            <Card>
              <CardContent className="pt-5">
                <h3 className="text-body-lg font-[510] text-fg">Fail closed</h3>
                <p className="mt-2 text-label text-fg-subtle">
                  A blanked threshold disables the operation rather than unbounding it. There is no
                  configuration state in which removing a limit grants more authority.
                </p>
              </CardContent>
            </Card>
          </div>
        </div>
      </MarketingSection>

      <MarketingSection
        eyebrow="The committed limits"
        title="Currency INR. Hard caps before approval thresholds, deliberately."
      >
        <Card>
          <CardContent className="overflow-x-auto p-0">
            <table className="w-full min-w-[32rem] border-collapse text-left">
              <thead>
                <tr className="border-b border-border-subtle">
                  <th className="px-4 py-2.5 text-caption font-[510] tracking-[0.04em] text-fg-subtle uppercase">
                    Rule
                  </th>
                  <th className="px-4 py-2.5 text-right text-caption font-[510] tracking-[0.04em] text-fg-subtle uppercase">
                    Limit
                  </th>
                  <th className="px-4 py-2.5 text-caption font-[510] tracking-[0.04em] text-fg-subtle uppercase">
                    Outcome above it
                  </th>
                </tr>
              </thead>
              <tbody>
                {LIMITS.map((l) => (
                  <tr key={l.rule} className="border-b border-border-subtle last:border-0">
                    <td className="px-4 py-3 font-mono text-label-sm text-fg-muted">{l.rule}</td>
                    <td className="tabular px-4 py-3 text-right text-label text-fg">{l.limit}</td>
                    <td className="px-4 py-3">
                      <Badge tone={l.tone}>
                        <span className="font-mono">{l.outcome}</span>
                      </Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </CardContent>
        </Card>
      </MarketingSection>

      <MarketingSection eyebrow="Accountability" title="A grant is not a blank cheque">
        <div className="grid gap-6 grid-cols-1 lg:grid-cols-2">
          <div className="rounded-lg border border-mode-test-border bg-mode-test-surface p-6">
            <p className="font-mono text-caption tracking-[0.1em] text-mode-test uppercase">
              Approval is a binding
            </p>
            <h3 className="mt-2.5 text-body-lg font-[510] text-fg">
              Redemption re-derives every field
            </h3>
            <p className="mt-2 text-label text-fg-subtle">
              An approval binds merchant, mode, operation, checkout, payment, amount and currency.
              Redemption re-derives every field from server-side facts and refuses on the first one
              that moved. It expires at its TTL whether or not it was granted.
            </p>
            <div className="mt-4 flex flex-wrap gap-2 font-mono text-label-sm">
              <Badge tone="warning">TTL 30 minutes</Badge>
              <Badge tone="outline">7 bound fields</Badge>
              <Badge tone="outline">single redemption</Badge>
            </div>
          </div>
          <Card>
            <CardContent className="pt-5">
              <p className="font-mono text-caption tracking-[0.1em] text-fg-subtle uppercase">
                Every attempt is recorded
              </p>
              <h3 className="mt-2.5 text-body-lg font-[510] text-fg">Answerable after the fact</h3>
              <p className="mt-2 text-label text-fg-subtle">
                Each action carries its tool, state, policy decision, approval, budget remaining,
                model and prompt version — plus the platform calls it made, with the idempotency key
                each one derived. A step marked REPLAYED is the evidence a retry did not
                double-charge.
              </p>
              <Button variant="secondary" size="md" asChild className="mt-4">
                <Link href="/docs#agentic">Read the policy model</Link>
              </Button>
            </CardContent>
          </Card>
        </div>
      </MarketingSection>

      <MarketingSection eyebrow="Scope" title="What this deliberately is not" bordered={false}>
        <Reveal stagger className="grid gap-4 grid-cols-1 md:grid-cols-3">
          {NOTS.map((n) => (
            <RevealItem key={n.title}>
              <Card className="h-full">
                <CardContent className="pt-5">
                  <h3 className="text-label font-[510] text-fg">{n.title}</h3>
                  <p className="mt-1.5 text-label text-fg-subtle">{n.body}</p>
                </CardContent>
              </Card>
            </RevealItem>
          ))}
        </Reveal>
      </MarketingSection>
    </>
  );
}
