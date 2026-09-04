import {
  ArrowRight,
  BookLock,
  Boxes,
  FlaskConical,
  GitBranch,
  ListChecks,
  Radio,
  RefreshCw,
  ShieldCheck,
  Terminal,
  Webhook,
} from 'lucide-react';
import type { Metadata } from 'next';
import Link from 'next/link';

import { Hero } from '@/components/marketing/hero';
import { Lifecycle } from '@/components/marketing/lifecycle';
import { Reveal, RevealItem } from '@/components/marketing/reveal';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { readSession } from '@/lib/session/require';
import { cn } from '@/lib/utils';

export const metadata: Metadata = {
  title: 'PaymentFlow — payments infrastructure for developers',
  description:
    'Idempotent payments, a double-entry ledger, signed webhooks and a versioned API. Build against test mode first.',
};

/**
 * The public landing page (M23.2a).
 *
 * ── What changed and why ──────────────────────────────────────────────────────────────
 *
 * M23.1 put a single-screen page here whose job was to prove the design system read correctly at
 * page scale — "Developer portal · M23", one link, to the component gallery. That was the right
 * artefact for a foundation milestone and the wrong front door for a product: it described the
 * milestone rather than the platform, and it offered a visitor no way to become a user.
 *
 * This page sells what the repository can actually do, and every claim on it is traceable:
 * idempotency records in `payment-service`, the transition table in `PaymentStatus`, the ledger
 * in `transaction-service`, HMAC-signed deliveries in `notification-service`, Resilience4j in
 * `common-lib`, the generated clients under `sdks/`, and a dated revision in `docs/openapi.yaml`.
 * Nothing here is aspirational, and no milestone identifier appears anywhere a visitor can see —
 * "M23.6" is a fact about our schedule, not about the product.
 *
 * ── Section ids are navigation contracts ──────────────────────────────────────────────
 *
 * `#platform`, `#lifecycle`, `#developers` and `#reliability` are what `SITE_NAV` points at, in
 * both the navbar and the footer. Renaming one here breaks two links, which is why the list lives
 * in `components/marketing/site-nav.ts` and is imported by both.
 */
export default async function LandingPage() {
  // Read rather than required: `/` renders for everyone. It decides which call to action is
  // honest, and nothing more — the page never shows a signed-in visitor anything about
  // themselves, so there is no per-user content here to leak.
  const signedIn = (await readSession()) !== null;

  return (
    <main id="main">
      <Hero signedIn={signedIn} />

      <Section
        id="platform"
        eyebrow="Platform"
        title="Everything a payment touches, in one place"
        lede="Four services behind one contract: authorization and capture, refunds, the ledger that records both, and the webhooks that tell your systems it happened."
      >
        <Reveal stagger className="grid gap-4 grid-cols-1 sm:grid-cols-2 lg:grid-cols-3">
          {CAPABILITIES.map(({ icon: Icon, title, body }) => (
            <RevealItem key={title}>
              <Card className="h-full">
                <CardContent className="pt-5">
                  <Icon aria-hidden className="size-4 text-fg-subtle" />
                  <p className="mt-3 text-label font-[510] text-fg">{title}</p>
                  <p className="mt-1.5 text-body text-fg-subtle">{body}</p>
                </CardContent>
              </Card>
            </RevealItem>
          ))}
        </Reveal>
      </Section>

      <Section
        id="lifecycle"
        eyebrow="Lifecycle"
        title="A payment is a state machine, and you can see all of it"
        lede="Illegal transitions are rejected rather than quietly coerced, so a payment is never in a state your integration cannot explain."
      >
        <Reveal>
          <Lifecycle />
        </Reveal>
      </Section>

      <Section
        id="agentic"
        eyebrow="Agentic Commerce"
        title="Make your catalogue transactable by AI buyers"
        lede="An agent runtime with a typed tool registry, a checkout that derives its own total, and an action log that records every attempt. The agent proposes; a deterministic policy engine decides, and the platform executes."
      >
        <Reveal stagger className="grid gap-4 grid-cols-1 sm:grid-cols-2">
          {AGENTIC_POINTS.map(({ icon: Icon, title, body }) => (
            <RevealItem key={title}>
              <Card className="h-full">
                <CardContent className="pt-5">
                  <Icon aria-hidden className="size-4 text-fg-subtle" />
                  <p className="mt-3 text-label font-[510] text-fg">{title}</p>
                  <p className="mt-1.5 text-body text-fg-subtle">{body}</p>
                </CardContent>
              </Card>
            </RevealItem>
          ))}
        </Reveal>
        <Reveal className="mt-4 grid gap-3 grid-cols-1 sm:grid-cols-3">
          {POLICY_BANDS.map(({ outcome, note, tone }) => (
            <div key={outcome} className="rounded-lg bg-surface p-4 ring-hairline">
              <span
                className={cn(
                  'inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-label-sm font-[510]',
                  tone === 'success' && 'bg-success-surface text-success',
                  tone === 'warning' && 'bg-warning-surface text-warning',
                  tone === 'danger' && 'bg-danger-surface text-danger',
                )}
              >
                {outcome}
              </span>
              <p className="mt-2 text-label text-fg-subtle">{note}</p>
            </div>
          ))}
        </Reveal>
        <Reveal className="mt-6">
          <Button variant="secondary" size="lg" asChild>
            <Link href="/agentic-commerce">
              How the policy engine works <ArrowRight />
            </Link>
          </Button>
        </Reveal>
      </Section>

      <Section
        id="developers"
        eyebrow="Developers"
        title="API first, and the clients prove it"
        lede="One OpenAPI document is the source of truth. The SDKs, this dashboard and the request validation are all generated or driven from it, so none of them can describe an API the platform is not serving."
      >
        <Reveal stagger className="grid gap-4 grid-cols-1 sm:grid-cols-2">
          {DEVELOPER_POINTS.map(({ icon: Icon, title, body }) => (
            <RevealItem key={title}>
              <Card className="h-full">
                <CardContent className="pt-5">
                  <Icon aria-hidden className="size-4 text-fg-subtle" />
                  <p className="mt-3 text-label font-[510] text-fg">{title}</p>
                  <p className="mt-1.5 text-body text-fg-subtle">{body}</p>
                </CardContent>
              </Card>
            </RevealItem>
          ))}
        </Reveal>
      </Section>

      <Section
        id="reliability"
        eyebrow="Reliability"
        title="Built for the requests that do not go well"
        lede="Retries, circuit breakers and an event log, because the interesting part of a payments platform is what it does when a downstream call times out."
      >
        <Reveal stagger className="grid gap-4 grid-cols-1 sm:grid-cols-3">
          {RELIABILITY_POINTS.map(({ icon: Icon, title, body }) => (
            <RevealItem key={title}>
              <Card className="h-full">
                <CardContent className="pt-5">
                  <Icon aria-hidden className="size-4 text-fg-subtle" />
                  <p className="mt-3 text-label font-[510] text-fg">{title}</p>
                  <p className="mt-1.5 text-body text-fg-subtle">{body}</p>
                </CardContent>
              </Card>
            </RevealItem>
          ))}
        </Reveal>
      </Section>

      <section className="mx-auto w-full max-w-6xl px-5 pb-28 sm:px-8">
        <Reveal>
          <div className="edge-light relative overflow-hidden rounded-xl bg-surface px-6 py-14 text-center ring-hairline sm:px-12">
            <div
              aria-hidden
              className="pointer-events-none absolute inset-x-0 top-0 h-40 bg-[radial-gradient(ellipse_50%_70%_at_50%_0%,var(--color-accent-subtle),transparent_70%)]"
            />
            <h2 className="relative text-title-2 font-[510] tracking-[-0.165px] text-balance text-fg sm:text-[2rem] sm:leading-[1.15] sm:tracking-[-0.6px]">
              {signedIn
                ? 'Everything is set up and waiting'
                : 'Start in test mode in about a minute'}
            </h2>
            <p className="relative mx-auto mt-3 max-w-md text-body text-pretty text-fg-subtle">
              {signedIn
                ? 'Your business account is ready. Pick up where you left off.'
                : 'Create an account, name your business, and you have keys. Nothing charges anything until you say so.'}
            </p>
            <div className="relative mt-7 flex flex-wrap items-center justify-center gap-3">
              <Button variant="primary" size="lg" asChild>
                <Link href={signedIn ? '/dashboard' : '/signup'}>
                  {signedIn ? 'Open the dashboard' : 'Create your account'} <ArrowRight />
                </Link>
              </Button>
              {signedIn ? null : (
                <Button variant="ghost" size="lg" asChild>
                  <Link href="/login">Sign in</Link>
                </Button>
              )}
            </div>
          </div>
        </Reveal>
      </section>
    </main>
  );
}

const AGENTIC_POINTS = [
  {
    icon: Boxes,
    title: 'A typed tool registry',
    body: 'Seven tools, each with a fixed risk class — READ, COMMERCE, PAYMENT, REFUND. The agent can only ask for one of these, and risk class is what decides which rules apply.',
  },
  {
    icon: ShieldCheck,
    title: 'A deterministic policy engine',
    body: 'Seventeen rules with one outcome each, versioned. Hard caps are checked before approval thresholds, so an amount beyond the outer bound is never offered to a person to wave through.',
  },
  {
    icon: ListChecks,
    title: 'A human approval workflow',
    body: 'Above the threshold, execution waits. An approval binds the merchant, amount and target, and redemption refuses on the first field that moved.',
  },
  {
    icon: GitBranch,
    title: 'An append-only action log',
    body: 'Every tool call, every rejected tool call and every policy decision is recorded — with the idempotency key each platform step derived, so a REPLAYED step proves a retry did not double-charge.',
  },
] as const;

const POLICY_BANDS = [
  {
    outcome: 'PERMIT',
    note: 'A refund up to ₹1,000 proceeds automatically.',
    tone: 'success' as const,
  },
  {
    outcome: 'REQUIRES APPROVAL',
    note: 'Above ₹1,000, a person decides — inside a 30-minute window.',
    tone: 'warning' as const,
  },
  {
    outcome: 'REFUSE',
    note: 'Above ₹20,000, refused outright. No approval permits it.',
    tone: 'danger' as const,
  },
] as const;

const CAPABILITIES = [
  {
    icon: RefreshCw,
    title: 'Idempotent by default',
    body: 'Every mutation takes an Idempotency-Key. Replay a request after a timeout and you get the original answer, not a second charge.',
  },
  {
    icon: GitBranch,
    title: 'Authorize, capture, refund',
    body: 'Hold funds, settle them later, return them in full or in part — each an explicit transition with its own event.',
  },
  {
    icon: BookLock,
    title: 'Double-entry ledger',
    body: 'A balance is derived from the entries that produced it rather than kept as a number beside them, so it can always be explained.',
  },
  {
    icon: Webhook,
    title: 'Signed webhooks',
    body: 'Every delivery carries an HMAC signature over the exact body sent, and the signing secret is encrypted at rest — never hashed, so you can always verify.',
  },
  {
    icon: FlaskConical,
    title: 'Test and live, never confused',
    body: 'Mode is bound to the credential, not to a header. A test key cannot read live data and no request can talk it into trying.',
  },
  {
    icon: ShieldCheck,
    title: 'Keys you can rotate',
    body: 'Publishable and secret keys per mode, stored hashed and revealed once, with rotation that leaves a grace window rather than an outage.',
  },
] as const;

const DEVELOPER_POINTS = [
  {
    icon: Boxes,
    title: 'Generated SDKs',
    body: 'TypeScript and Python clients emitted from the same document that describes the API, with a build gate that fails when they drift from it.',
  },
  {
    icon: Terminal,
    title: 'A dated, versioned contract',
    body: 'Requests name the revision they were written against. Breaking changes ship as a new date, and the old shape keeps being served.',
  },
  {
    icon: FlaskConical,
    title: 'A sandbox that decides on purpose',
    body: 'Test cards choose their own outcome — approvals, declines, timeouts — so the failure paths are as easy to build against as the happy one.',
  },
  {
    icon: Radio,
    title: 'Everything is observable',
    body: 'Traces, metrics and a per-request log across every service, correlated by one id from the gateway inwards.',
  },
] as const;

const RELIABILITY_POINTS = [
  {
    icon: RefreshCw,
    title: 'Retries with a memory',
    body: 'Retries are bounded, jittered, and safe — the idempotency layer is what makes a repeated request a repeat rather than a duplicate.',
  },
  {
    icon: ShieldCheck,
    title: 'Circuit breakers',
    body: 'A failing dependency is isolated rather than waited on, so one slow service does not become a queue of stuck payments.',
  },
  {
    icon: Radio,
    title: 'Event-driven, not batch',
    body: 'State changes are published as events the moment they commit, which is how the ledger, the webhooks and the analytics stay in step.',
  },
] as const;

/**
 * One band of the page.
 *
 * The vertical rhythm is here rather than at each call site, because a landing page whose
 * sections are 96px, 112px and 80px apart is the single most reliable way to make an otherwise
 * careful design read as assembled by hand.
 */
function Section({
  id,
  eyebrow,
  title,
  lede,
  children,
}: {
  id: string;
  eyebrow: string;
  title: string;
  lede: string;
  children: React.ReactNode;
}) {
  return (
    <section
      id={id}
      // The offset keeps a heading clear of the sticky navbar when an anchor jumps to it —
      // otherwise every in-page link lands with the title hidden behind the bar.
      className="mx-auto w-full max-w-6xl scroll-mt-20 px-5 py-20 sm:px-8 sm:py-24"
    >
      <Reveal className="mb-10 max-w-2xl">
        <p className="text-label font-[510] text-accent-text">{eyebrow}</p>
        <h2 className="mt-2 text-title-2 font-[510] tracking-[-0.165px] text-balance text-fg sm:text-[2rem] sm:leading-[1.15] sm:tracking-[-0.6px]">
          {title}
        </h2>
        <p className="mt-3 text-body text-pretty text-fg-subtle">{lede}</p>
      </Reveal>

      {children}
    </section>
  );
}
