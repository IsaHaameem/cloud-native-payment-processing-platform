import Link from 'next/link';
import type { Metadata } from 'next';

import { MarketingHero } from '@/components/marketing/marketing-hero';
import { MarketingSection } from '@/components/marketing/marketing-section';
import { Reveal, RevealItem } from '@/components/marketing/reveal';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';

export const metadata: Metadata = {
  title: 'Security',
  description:
    'What the platform enforces today, described in the terms an auditor would use — with known limitations labelled as limitations, not controls.',
};

/** Reference: `Security.dc.html`. Limitations map to `frontend_Design.md §38` (G-numbers). */

const CONTROLS = [
  {
    tag: 'Credentials',
    tone: 'success' as const,
    title: 'Reveal once, then only a prefix',
    body: 'A secret key is displayed exactly once, at creation or rotation, behind an explicit acknowledgement. Afterwards only its prefix is shown, because no reversible copy is retained. Revoking a live key requires typed confirmation.',
  },
  {
    tag: 'Browser boundary',
    tone: 'success' as const,
    title: 'The browser never holds a token',
    body: 'The dashboard calls the portal; the portal attaches the session credential server-side. The read proxy accepts GET operations only, derived from the generated contract, so no mutation is reachable from client code. Mutations run as server actions with CSRF and origin assertion.',
  },
  {
    tag: 'Tenancy',
    tone: 'success' as const,
    title: 'Cross-tenant reads are 404, not 403',
    body: 'A record belonging to another merchant returns not-found rather than forbidden, so the existence of an identifier cannot be probed. Every query is merchant and mode scoped at the repository layer.',
  },
  {
    tag: 'Webhooks',
    tone: 'success' as const,
    title: 'Signed over the exact bytes',
    body: 'Each delivery is signed over the transmitted body. Secrets are rotatable, endpoints auto-disable after repeated failure with the reason recorded, and a URL cannot be repointed — that would leave delivery history attached to a destination that never received any of it.',
  },
  {
    tag: 'Redaction',
    tone: 'info' as const,
    title: 'Redacted before storage, not on display',
    body: 'Agent input summaries, failure messages and tool results are redacted before they are written. The dashboard shows a redaction chip with no reveal affordance, because there is nothing to reveal.',
  },
  {
    tag: 'Mode separation',
    tone: 'warning' as const,
    title: 'Test can never look like production',
    body: 'Mode is a property of the credential, not a request parameter. Test mode carries a persistent banner and per-row chips in a palette reserved for it, and a demonstration provider decision is never styled as a successful authorization.',
  },
];

const SECRETS = [
  ['provider key-id and key-secret', 'Provider credentials never leave server configuration'],
  ['internal-context HMAC secret', 'Signs service-to-service context'],
  ['LLM API key', 'Agent runtime credential'],
  ['platform API key', 'Used by the agentic service internally'],
  ['webhook signing secret', 'Shown once at creation or rotation; prefix only thereafter'],
];

const GAPS = [
  {
    tag: 'Blocker',
    title: 'The agentic API is not gateway-routed or authenticated',
    body: 'Four agentic endpoints are currently reachable without a credential and are not fronted by the gateway. Until that closes, the portal calls them server-side only from an allowlisted base URL, and the section is feature-flagged off by default.',
  },
  {
    tag: 'Open',
    title: 'No roles, teams or per-user permissions',
    body: 'The identity model is one user per merchant, and a portal session receives the full scope set. Role-gated interfaces are therefore not offered rather than being faked.',
  },
  {
    tag: 'Open',
    title: 'Provider decisions are not persisted',
    body: 'External provider decisions are returned but not stored, so the demo-decision callout can only be rendered from the sandbox decision log today. The dashboard marks that panel unavailable rather than inferring the outcome.',
  },
];

export default function SecurityPage() {
  return (
    <>
      <MarketingHero
        eyebrow="Security"
        title="Controls you can verify, stated without embellishment."
        lede="Below is what the platform enforces today, described in the same terms an auditor would use. Where something is a known limitation rather than a control, it is labelled as one."
      />

      <MarketingSection eyebrow="Controls" title="Enforced today">
        <Reveal stagger className="grid gap-4 grid-cols-1 md:grid-cols-2 lg:grid-cols-3">
          {CONTROLS.map((c) => (
            <RevealItem key={c.tag}>
              <Card className="h-full">
                <CardContent className="pt-5">
                  <Badge tone={c.tone} dot>
                    {c.tag}
                  </Badge>
                  <h2 className="mt-3 text-label font-[510] text-fg">{c.title}</h2>
                  <p className="mt-2 text-label text-fg-subtle">{c.body}</p>
                </CardContent>
              </Card>
            </RevealItem>
          ))}
        </Reveal>
      </MarketingSection>

      <MarketingSection
        eyebrow="Secrets"
        title="What is never shown"
        lede="The dashboard cannot display these values because no surface returns them."
      >
        <Card>
          <CardContent className="divide-y divide-border-subtle p-0">
            {SECRETS.map(([key, note]) => (
              <div key={key} className="flex flex-wrap items-center gap-3 p-4">
                <span className="min-w-0 flex-1 font-mono text-label-sm break-words text-fg-muted">
                  {key}
                </span>
                <Badge tone="danger">never displayed</Badge>
                <span className="min-w-0 flex-1 text-label text-fg-subtle">{note}</span>
              </div>
            ))}
            <p className="p-4 text-label text-fg-subtle">
              A configuration screen reports{' '}
              <code className="font-mono text-fg-muted">configured</code> or{' '}
              <code className="font-mono text-fg-muted">not configured</code> and nothing more — no
              value in a response, a JSON dump, a copy action or an error message.
            </p>
          </CardContent>
        </Card>
      </MarketingSection>

      <MarketingSection eyebrow="Honesty" title="What is not yet true">
        <div className="rounded-lg border border-mode-test-border bg-mode-test-surface p-6">
          <p className="max-w-2xl text-label text-fg-muted">
            A security page that lists only strengths is not a security page. These are open items,
            tracked and named.
          </p>
          <div className="mt-4 divide-y divide-mode-test-border">
            {GAPS.map((g) => (
              <div key={g.title} className="flex flex-wrap gap-3 py-3">
                <Badge tone={g.tag === 'Blocker' ? 'danger' : 'warning'}>
                  <span className="font-mono">{g.tag}</span>
                </Badge>
                <div className="min-w-[16rem] flex-1">
                  <p className="text-label text-fg">{g.title}</p>
                  <p className="text-label-sm text-fg-subtle">{g.body}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </MarketingSection>

      <MarketingSection eyebrow="Disclosure" title="Reporting a vulnerability" bordered={false}>
        <Card>
          <CardContent className="flex flex-wrap items-center justify-between gap-4 pt-5">
            <p className="max-w-xl text-label text-fg-subtle">
              Send findings to the security contact with reproduction steps and the{' '}
              <code className="font-mono text-fg-muted">requestId</code> from any affected call. We
              acknowledge, triage, and tell you what we found.
            </p>
            <Button variant="primary" size="lg" asChild>
              <Link href="/contact">Contact the team</Link>
            </Button>
          </CardContent>
        </Card>
      </MarketingSection>
    </>
  );
}
