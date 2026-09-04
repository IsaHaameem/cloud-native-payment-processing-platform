import type { Metadata } from 'next';

import { MarketingHero } from '@/components/marketing/marketing-hero';
import { MarketingSection } from '@/components/marketing/marketing-section';
import { Reveal, RevealItem } from '@/components/marketing/reveal';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { CodeBlock } from '@/components/ui/code-block';
import { API_VERSION } from '@/generated/contract';

export const metadata: Metadata = {
  title: 'Developers',
  description:
    'One generated contract behind both SDKs, idempotency required by design, cursor pagination that cannot skip rows, and a sandbox with reproducible failures instead of a happy path.',
};

/** Reference: `Developer Platform.dc.html`. */

const NODE = `npm install paymentflow

import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({
  apiKey: process.env.PAYMENTFLOW_API_KEY,
});

const payment = await client.payments.create({
  amountMinor: 649000,      // ₹6,490.00
  currency: 'INR',
  description: 'Order A-1234',
  metadata: { orderId: 'A-1234' },
});

await client.payments.authorize(payment.id ?? '');
await client.payments.capture(payment.id ?? '');`;

const PY = `pip install paymentflow

from paymentflow import PaymentFlow

client = PaymentFlow()   # reads PAYMENTFLOW_API_KEY

payment = client.payments.create(
    amount_minor=649000,      # ₹6,490.00
    currency="INR",
    description="Order A-1234",
    metadata={"orderId": "A-1234"},
)

client.payments.authorize(payment["id"])
client.payments.capture(payment["id"])`;

const ERR = `{
  "code": "insufficient_funds",
  "message": "The card has insufficient funds.",
  "type": "card_error",
  "param": null,
  "requestId": "req_7c1e04b9",
  "correlationId": "cor_7d1e9a04",
  "docUrl": "…",
  "path": "/v1/payments/{id}/authorize"
}`;

const TIERS = [
  {
    name: 'Public API',
    path: '/v1/**',
    cred: 'Authorization: Bearer sk_test_… / sk_live_…',
    who: 'Your own server and the SDKs. Scoped by key.',
    tone: 'success' as const,
  },
  {
    name: 'Dashboard API',
    path: '/api/v1/**',
    cred: 'Session JWT from the identity service',
    who: 'The developer portal only. Never your integration.',
    tone: 'info' as const,
  },
  {
    name: 'Internal',
    path: '/internal/v1/**',
    cred: 'HMAC-signed internal context',
    who: 'Service to service. Not reachable from a browser at all.',
    tone: 'neutral' as const,
  },
];

const RULES = [
  {
    tag: 'Idempotency',
    title: 'One key per logical attempt',
    body: 'Generate a UUID per attempt, hold it for that attempt’s lifetime, and send the same key on every retry of it. A new key means a new payment.',
  },
  {
    tag: 'Pagination',
    title: 'Cursors, never offsets',
    body: 'starting_after is opaque and signed. Never parse, construct, cache-key on or reorder it. Offset paging silently skips rows under concurrent inserts.',
  },
  {
    tag: 'Webhooks',
    title: 'Verify the bytes you received',
    body: 'The signature covers the exact bytes sent. Parsing to JSON and re-serialising before verification will fail — verify first, then parse.',
  },
  {
    tag: 'Versioning',
    title: 'Pin your version',
    body: `Send PaymentFlow-Version and leave it pinned. Following the platform’s current revision automatically is what exposes you to a breaking change.`,
  },
];

const SCOPES = [
  'payments:read',
  'payments:write',
  'webhooks:manage',
  'balance:read',
  'events:read',
  'analytics:read',
  'logs:read',
];

const SANDBOX = [
  '17 test instruments, each with a fixed outcome, decline code and capture behaviour',
  'Simulation overrides for scenario, decline code, latency and remaining count',
  'A decision log you can query per payment',
  'A demo provider decision is never labelled as an authorization — no card was charged and the UI says so',
];

export default function DevelopersPage() {
  return (
    <>
      <MarketingHero
        eyebrow="Developers"
        title="Integrate it in an afternoon. Trust it in production."
        lede="One generated contract behind both SDKs, idempotency required by design, cursor pagination that cannot skip rows, and a sandbox with reproducible failures instead of a happy path."
        actions={[
          { label: 'Get a test key', href: '/signup', variant: 'primary' },
          { label: 'Read the docs', href: '/docs' },
        ]}
        aside={
          <CodeBlock
            samples={[
              { label: 'Node.js', code: NODE, filename: 'payment.ts' },
              { label: 'Python', code: PY, filename: 'payment.py' },
            ]}
            caption="Install commands are illustrative — the packages are not yet published to a public registry."
          />
        }
      />

      <MarketingSection
        eyebrow="API tiers"
        title="Two API tiers, and they are not the same thing"
        lede="Knowing which one you are calling is the first thing to get right."
      >
        <Card>
          <CardContent className="divide-y divide-border-subtle p-0">
            {TIERS.map((t) => (
              <div key={t.name} className="flex flex-wrap gap-4 p-4 sm:p-5">
                <div className="min-w-[10rem] flex-1">
                  <p className="flex items-center gap-2 text-label font-[510] text-fg">
                    <Badge tone={t.tone} dot>
                      {t.name}
                    </Badge>
                  </p>
                  <p className="mt-1 font-mono text-label-sm text-fg-subtle">{t.path}</p>
                </div>
                <p className="min-w-[12rem] flex-1 font-mono text-label-sm break-words text-fg-muted">
                  {t.cred}
                </p>
                <p className="min-w-[12rem] flex-1 text-label text-fg-subtle">{t.who}</p>
              </div>
            ))}
          </CardContent>
        </Card>
      </MarketingSection>

      <MarketingSection eyebrow="Discipline" title="Four rules that will save you an incident">
        <Reveal stagger className="grid gap-4 grid-cols-1 sm:grid-cols-2">
          {RULES.map((r) => (
            <RevealItem key={r.tag}>
              <Card className="h-full">
                <CardContent className="pt-5">
                  <p className="font-mono text-caption tracking-[0.08em] text-fg-subtle uppercase">
                    {r.tag}
                  </p>
                  <h3 className="mt-2.5 text-label font-[510] text-fg">{r.title}</h3>
                  <p className="mt-2 text-label text-fg-subtle">{r.body}</p>
                </CardContent>
              </Card>
            </RevealItem>
          ))}
        </Reveal>
      </MarketingSection>

      <MarketingSection eyebrow="Credentials & sandbox" title="Scoped keys, reproducible failures">
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-[1.3fr_1fr]">
          <Card>
            <CardContent className="pt-5">
              <h3 className="text-body-lg font-[510] text-fg">Key scopes</h3>
              <p className="mt-1 text-label text-fg-subtle">
                Seven scopes. A key carries only what it needs.
              </p>
              <div className="mt-4 flex flex-wrap gap-2">
                {SCOPES.map((s) => (
                  <Badge key={s} tone="outline">
                    <span className="font-mono">{s}</span>
                  </Badge>
                ))}
              </div>
              <p className="mt-4 text-label text-fg-subtle">
                A secret is shown exactly once, at creation or rotation. Afterwards only its prefix
                is ever displayed, because the platform stores no reversible copy.
              </p>
            </CardContent>
          </Card>
          <div className="rounded-lg border border-mode-test-border bg-mode-test-surface p-5">
            <h3 className="text-body-lg font-[510] text-mode-test">Sandbox</h3>
            <p className="mt-1 text-label text-fg-muted">
              Reproducible failures, not a happy path.
            </p>
            <ul className="mt-3 space-y-2">
              {SANDBOX.map((text) => (
                <li key={text} className="flex gap-2 text-label text-fg-subtle">
                  <span aria-hidden className="mt-2 size-1.5 shrink-0 rounded-full bg-mode-test" />
                  {text}
                </li>
              ))}
            </ul>
          </div>
        </div>
      </MarketingSection>

      <MarketingSection
        eyebrow="Errors"
        title="Every error is the same shape"
        lede="Branch on type — the small, stable half — rather than code, which grows as policy does. Always surface requestId: it is the handle support will ask for."
        bordered={false}
      >
        <CodeBlock samples={[{ label: 'json', code: ERR, filename: 'error.json' }]} />
        <div className="mt-4 flex flex-wrap gap-2 font-mono text-label-sm">
          <Badge tone="info">409 IDEMPOTENCY_CONFLICT is retryable</Badge>
          <Badge tone="outline">cross-tenant reads are 404, never 403</Badge>
          <Badge tone="outline">PaymentFlow-Version: {API_VERSION}</Badge>
        </div>
      </MarketingSection>
    </>
  );
}
