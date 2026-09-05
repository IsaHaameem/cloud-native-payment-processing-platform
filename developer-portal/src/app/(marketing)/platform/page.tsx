import type { Metadata } from 'next';

import { MarketingHero } from '@/components/marketing/marketing-hero';
import { MarketingSection } from '@/components/marketing/marketing-section';
import { Reveal, RevealItem } from '@/components/marketing/reveal';
import { StatusPill } from '@/components/patterns/status-pill';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';

export const metadata: Metadata = {
  title: 'Platform',
  description:
    'Ten deployable services behind one gateway. Every call authenticated, scoped and signed onward; every mutation idempotent; every state change an explicit transition that posts to a ledger and emits an event.',
};

/**
 * The Platform marketing page (frontend build).
 *
 * Reference: `Platform.dc.html`. The design's canvas particle field is dropped per the project's
 * motion rules; the content it framed — the seven recorded stages, the transition table, the six
 * guarantees — is what carries the page, and every claim is traceable to a service in the repo.
 */

const CHAIN = [
  {
    step: 'Request',
    tone: 'neutral' as const,
    note: 'A public call with a scoped secret key.',
    tech: 'Bearer sk_test_… · /v1/**',
  },
  {
    step: 'Gateway',
    tone: 'info' as const,
    note: 'Authenticates the key, resolves scope and mode, signs an internal context onward.',
    tech: 'HMAC internal context',
  },
  {
    step: 'Payment',
    tone: 'info' as const,
    note: 'The explicit FSM, idempotency, and the provider adapters.',
    tech: 'payment-service',
  },
  {
    step: 'Provider',
    tone: 'info' as const,
    note: 'The adapter decision, recorded verbatim including decline codes.',
    tech: 'ProviderDecision',
  },
  {
    step: 'Ledger',
    tone: 'success' as const,
    note: 'Double-entry posting. The balance is a projection of it.',
    tech: 'transaction-service',
  },
  {
    step: 'Event',
    tone: 'success' as const,
    note: 'The merchant-facing feed, ordered by creation. A redelivery cannot reorder it.',
    tech: 'evt_ + 32 hex',
  },
  {
    step: 'Webhook',
    tone: 'success' as const,
    note: 'A signed delivery with attempt history and replay.',
    tech: 'PaymentFlow-Signature',
  },
];

const STATES = [
  'created',
  'authorized',
  'captured',
  'partially_refunded',
  'refunded',
  'failed',
  'voided',
];

const TRANSITIONS = [
  { op: 'authorize', rule: 'created → authorized' },
  { op: 'capture', rule: 'authorized → captured' },
  { op: 'void', rule: 'authorized → voided' },
  { op: 'refund', rule: 'captured | partially_refunded → refunded' },
  { op: 'illegal', rule: 'a typed error — no state is written' },
];

const PILLARS = [
  {
    tag: 'Idempotency',
    title: 'A retry is not a second payment',
    body: 'Every mutation requires a key. Resend the same key and the platform returns the original response instead of performing the operation again.',
    tech: '409 IDEMPOTENCY_CONFLICT means still processing — retry the same key',
  },
  {
    tag: 'Ledger',
    title: 'Balance is derived, never stored',
    body: 'Each capture and refund posts paired debit and credit legs. What you see is a projection of those postings.',
    tech: 'GET /v1/balance · /v1/balance_transactions',
  },
  {
    tag: 'Provider abstraction',
    title: 'Acquirer codes survive intact',
    body: 'A failure keeps the provider’s own code. The platform never paraphrases insufficient_funds into card_declined.',
    tech: 'failureReason rendered verbatim',
  },
  {
    tag: 'Events',
    title: 'The webhook body and the event are the same bytes',
    body: 'An event id is byte-identical to the id in the delivered webhook, so a delivery reconciles against the feed without storing anything.',
    tech: 'GET /v1/events/{id}',
  },
  {
    tag: 'Delivery',
    title: 'Failures are visible, not silent',
    body: 'Every attempt records its status, latency and response. Endpoints auto-disable after consecutive failures, with the reason attached.',
    tech: 'consecutiveFailureCount · disabledReason · replay',
  },
  {
    tag: 'Observability',
    title: 'Every call has a support handle',
    body: 'Errors carry a code, a type and a requestId that resolves in request logs. Branch on type; it is the stable half.',
    tech: 'GET /v1/request_logs?request_id=…',
  },
];

export default function PlatformPage() {
  return (
    <>
      <MarketingHero
        eyebrow="Platform"
        title="Payment infrastructure built for deterministic execution."
        lede="Ten deployable services behind one gateway. Every public call is authenticated, resolved to a scope and a mode, then signed onward. Every mutation carries an idempotency key. Every state change is an explicit transition that posts to a ledger and emits an event."
        actions={[
          { label: 'Get started', href: '/signup', variant: 'primary' },
          { label: 'Read the API', href: '/docs' },
        ]}
      />

      <MarketingSection
        eyebrow="One request"
        title="Seven recorded stages"
        lede="Each stage is separately inspectable in the dashboard. Nothing is inferred after the fact."
      >
        <Reveal
          stagger
          className="grid gap-3 grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
        >
          {CHAIN.map((c) => (
            <RevealItem key={c.step}>
              <Card className="h-full">
                <CardContent className="space-y-2 pt-5">
                  <Badge tone={c.tone} dot>
                    {c.step}
                  </Badge>
                  <p className="text-label text-fg-subtle">{c.note}</p>
                  <p className="font-mono text-label-sm break-words text-fg-muted">{c.tech}</p>
                </CardContent>
              </Card>
            </RevealItem>
          ))}
        </Reveal>
      </MarketingSection>

      <MarketingSection
        eyebrow="State machine"
        title="A status column cannot enforce anything"
        lede="PaymentFlow holds an explicit transition table. A capture is only legal from authorized; a void is illegal once captured. Attempting an illegal transition is a typed error, not a silent write."
      >
        <div className="grid gap-6 grid-cols-1 lg:grid-cols-2">
          <div className="flex flex-wrap gap-2">
            {STATES.map((state) => (
              <StatusPill key={state} status={state} family="payment" dot />
            ))}
          </div>
          <Card>
            <CardContent className="pt-5">
              <p className="font-mono text-label-sm tracking-[0.06em] text-fg-subtle uppercase">
                Legal transitions
              </p>
              <dl className="mt-3 divide-y divide-border-subtle">
                {TRANSITIONS.map((t) => (
                  <div
                    key={t.op}
                    className="flex flex-wrap gap-x-4 gap-y-1 py-2.5 font-mono text-label-sm"
                  >
                    <dt className="w-24 shrink-0 text-fg">{t.op}</dt>
                    <dd className="text-fg-subtle">{t.rule}</dd>
                  </div>
                ))}
              </dl>
              <p className="mt-3 text-label-sm text-fg-subtle">
                Every one of these requires an{' '}
                <code className="font-mono text-fg-muted">Idempotency-Key</code> header.
              </p>
            </CardContent>
          </Card>
        </div>
      </MarketingSection>

      <MarketingSection eyebrow="Guarantees" title="What the platform enforces">
        <Reveal stagger className="grid gap-4 grid-cols-1 md:grid-cols-2 lg:grid-cols-3">
          {PILLARS.map((p) => (
            <RevealItem key={p.tag}>
              <Card className="h-full">
                <CardContent className="pt-5">
                  <p className="font-mono text-caption tracking-[0.1em] text-fg-subtle uppercase">
                    {p.tag}
                  </p>
                  <h3 className="mt-2.5 text-body-lg font-[510] text-fg">{p.title}</h3>
                  <p className="mt-2 text-label text-fg-subtle">{p.body}</p>
                  <p className="mt-3 border-t border-border-subtle pt-3 font-mono text-label-sm break-words text-fg-muted">
                    {p.tech}
                  </p>
                </CardContent>
              </Card>
            </RevealItem>
          ))}
        </Reveal>
      </MarketingSection>

      <MarketingSection
        center
        bordered={false}
        title="Test mode is the default"
        lede="Nothing you build moves real money — the platform settles against a simulated acquirer in both test and live mode, and a demonstration provider decision is never labelled as an authorization."
      >
        <div className="flex flex-wrap justify-center gap-3">
          <a
            href="/signup"
            className="inline-flex h-10 items-center rounded-md bg-accent px-4 text-label font-[510] text-fg-on-accent transition-colors hover:bg-accent-hover"
          >
            Start building
          </a>
          <a
            href="/agentic-commerce"
            className="inline-flex h-10 items-center rounded-md bg-surface-elevated px-4 text-label font-[510] text-fg ring-hairline transition-colors hover:bg-surface-active"
          >
            Agentic Commerce
          </a>
        </div>
      </MarketingSection>
    </>
  );
}
