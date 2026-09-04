import Link from 'next/link';
import type { Metadata } from 'next';

import { StatusPill } from '@/components/patterns/status-pill';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { CodeBlock } from '@/components/ui/code-block';
import { API_VERSION } from '@/generated/contract';

export const metadata: Metadata = {
  title: 'Documentation',
  description:
    'The reference is generated from the same contract the SDKs are built from. Start with the quickstart; reach for the vocabularies when a status or an error code surprises you.',
};

/** Reference: `Docs.dc.html`. */

const RAIL = [
  { id: 'getting-started', name: 'Getting started' },
  { id: 'authentication', name: 'Authentication' },
  { id: 'payments', name: 'Payments' },
  { id: 'idempotency', name: 'Idempotency' },
  { id: 'reference', name: 'API reference' },
  { id: 'webhooks', name: 'Webhooks & events' },
  { id: 'guides', name: 'Guides' },
  { id: 'agentic', name: 'Agentic & policies' },
  { id: 'sandbox', name: 'SDKs & sandbox' },
];

const REQ = `POST /v1/payments
Authorization: Bearer sk_test_••••
Idempotency-Key: 7f1c9a04-5c22
PaymentFlow-Version: ${API_VERSION}

{
  "amountMinor": 649000,
  "currency": "INR",
  "description": "Order A-1234",
  "metadata": { "orderId": "A-1234" }
}`;

const RES = `{
  "id": "b1727ebf-82de-4f39-bf05-15b25641da1d",
  "status": "created",
  "amountMinor": 649000,
  "capturedAmountMinor": 0,
  "currency": "INR",
  "mode": "test",
  "createdAt": "2026-08-23T14:02:11Z"
}`;

const HOOK = `const raw = await req.text();   // never req.json()

const ok = paymentflow.webhooks.verify(
  raw,
  req.headers.get('PaymentFlow-Signature'),
  process.env.PAYMENTFLOW_WEBHOOK_SECRET,
);

if (!ok) return new Response(null, { status: 400 });
const event = JSON.parse(raw);`;

const NODE = `npm install paymentflow

import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({
  apiKey: process.env.PAYMENTFLOW_API_KEY,
});

const payment = await client.payments.create({
  amountMinor: 649000,
  currency: 'INR',
});

await client.payments.authorize(payment.id);
await client.payments.capture(payment.id);`;

const PY = `pip install paymentflow

from paymentflow import PaymentFlow

client = PaymentFlow()   # reads PAYMENTFLOW_API_KEY

payment = client.payments.create(
    amount_minor=649000,
    currency="INR",
)

client.payments.authorize(payment["id"])
client.payments.capture(payment["id"])`;

const PARAMS = [
  [
    'amountMinor',
    'integer',
    'required',
    'Integer in the currency’s minor unit. 649000 INR is ₹6,490.00.',
  ],
  ['currency', 'string', 'required', 'ISO 4217, uppercase. Determines the minor-unit exponent.'],
  [
    'description',
    'string',
    'optional',
    'Redacted before storage. Returned as [REDACTED] in events.',
  ],
  ['metadata', 'object', 'optional', 'String keys and values. Filterable as metadata[key]=value.'],
  [
    'paymentMethodToken',
    'string',
    'optional',
    'Required before authorize. A sandbox token in test mode.',
  ],
  [
    'Idempotency-Key',
    'header',
    'required',
    'One key per logical attempt. Reuse it on every retry.',
  ],
] as const;

type EndpointRow = readonly [method: string, path: string];
const GROUPS: readonly { name: string; scope: string; rows: readonly EndpointRow[] }[] = [
  {
    name: 'Payments',
    scope: 'payments:read · payments:write',
    rows: [
      ['GET', '/v1/payments'],
      ['GET', '/v1/payments/{id}'],
      ['POST', '/v1/payments/{id}/authorize'],
      ['POST', '/v1/payments/{id}/capture'],
      ['POST', '/v1/payments/{id}/void'],
      ['POST', '/v1/payments/{id}/refund'],
    ],
  },
  {
    name: 'Refunds & balance',
    scope: 'payments:read · balance:read',
    rows: [
      ['GET', '/v1/refunds'],
      ['GET', '/v1/refunds/{id}'],
      ['GET', '/v1/balance'],
      ['GET', '/v1/balance_transactions'],
    ],
  },
  {
    name: 'Webhooks',
    scope: 'webhooks:manage',
    rows: [
      ['POST', '/v1/webhook_endpoints'],
      ['PATCH', '/v1/webhook_endpoints/{id}'],
      ['POST', '/v1/webhook_endpoints/{id}/rotate_secret'],
      ['GET', '/v1/webhook_deliveries'],
      ['POST', '/v1/webhook_deliveries/{id}/replay'],
    ],
  },
  {
    name: 'Events & logs',
    scope: 'events:read · logs:read',
    rows: [
      ['GET', '/v1/events'],
      ['GET', '/v1/events/{id}'],
      ['GET', '/v1/request_logs'],
      ['GET', '/v1/usage'],
    ],
  },
  {
    name: 'Sandbox',
    scope: 'payments:read · payments:write',
    rows: [
      ['GET', '/v1/test/cards'],
      ['GET', '/v1/test/decisions'],
      ['POST', '/v1/test/simulations'],
      ['DELETE', '/v1/test/simulations/active'],
    ],
  },
  {
    name: 'Analytics',
    scope: 'analytics:read',
    rows: [
      ['GET', '/v1/analytics/payments'],
      ['GET', '/v1/usage'],
    ],
  },
];

const METHOD_TONE: Record<string, 'success' | 'info' | 'warning' | 'danger'> = {
  GET: 'success',
  POST: 'info',
  PATCH: 'warning',
  DELETE: 'danger',
};

const EVENT_TYPES = [
  'payment.created',
  'payment.authorized',
  'payment.captured',
  'payment.failed',
  'payment.refunded',
  'payment.partially_refunded',
  'payment.voided',
];

const GUIDES = [
  [
    'Idempotency',
    'One key per logical attempt, reused on every retry. A new key is a new payment.',
  ],
  ['Cursor pagination', 'Follow nextCursor until hasMore is false. Never parse or construct one.'],
  [
    'Verifying a webhook',
    'The signature covers the bytes that were sent. Verify before you parse.',
  ],
  [
    'Minor units',
    'Amounts are integers in the minor unit. 649000 INR is ₹6,490.00. Never parse a decimal.',
  ],
  [
    'Version pinning',
    'Send the version header explicitly rather than following the current revision.',
  ],
] as const;

const POLICY_BANDS = [
  {
    outcome: 'PERMIT',
    band: '₹0 – ₹1,000',
    note: 'Proceeds automatically.',
    tone: 'success' as const,
  },
  {
    outcome: 'REQUIRES APPROVAL',
    band: '₹1,000 – ₹20,000',
    note: 'Held for a person, 30-minute TTL.',
    tone: 'warning' as const,
  },
  {
    outcome: 'REFUSE',
    band: '> ₹20,000',
    note: 'Refused outright. No approval permits it.',
    tone: 'danger' as const,
  },
];

export default function DocsPage() {
  return (
    <div className="mx-auto flex w-full max-w-6xl gap-10 px-5 py-14 sm:px-8">
      <nav aria-label="Documentation" className="sticky top-24 hidden h-fit w-52 shrink-0 lg:block">
        <p className="border-b border-border-subtle pb-2 font-mono text-caption tracking-[0.1em] text-fg-subtle uppercase">
          Documentation
        </p>
        <ul className="mt-2 space-y-0.5">
          {RAIL.map((r) => (
            <li key={r.id}>
              <a
                href={`#${r.id}`}
                className="block rounded-md px-2 py-1.5 text-label text-fg-subtle transition-colors hover:bg-surface-hover hover:text-fg"
              >
                {r.name}
              </a>
            </li>
          ))}
        </ul>
        <div className="mt-4 space-y-1.5 border-t border-border-subtle pt-3 text-label">
          <Link href="/developers" className="block text-fg-subtle hover:text-fg">
            Developer platform →
          </Link>
          <Link href="/contact" className="block text-fg-subtle hover:text-fg">
            Ask a question →
          </Link>
        </div>
      </nav>

      <main className="min-w-0 flex-1 space-y-14">
        <section id="getting-started" className="scroll-mt-24">
          <p className="font-mono text-caption tracking-[0.12em] text-fg-subtle uppercase">
            Documentation
          </p>
          <h1 className="mt-3.5 text-[2rem] leading-[1.05] font-[510] tracking-[-0.03em] text-balance text-fg sm:text-[2.75rem]">
            Two ways in: get started, or go deep.
          </h1>
          <p className="mt-4 max-w-[60ch] text-body-lg text-pretty text-fg-subtle">
            New to PaymentFlow? Follow the guided quickstart — or let a coding agent do the
            integration. Building something specific? The reference below is generated from the same
            contract the SDKs are built from.
          </p>

          <div className="mt-6 grid gap-4 grid-cols-1 md:grid-cols-2">
            <div className="rounded-xl bg-surface p-5 ring-hairline">
              <p className="font-mono text-caption tracking-[0.08em] text-fg-subtle uppercase">
                Beginner
              </p>
              <p className="mt-2.5 text-body-lg font-[510] text-fg">Get started</p>
              <p className="mt-1.5 text-label text-fg-subtle">
                Create an account, get a test key, and send your first payment in about five
                minutes. The quickstart and the AI-integration prompt generator live in your
                dashboard.
              </p>
              <Link
                href="/signup"
                className="mt-4 inline-flex h-9 items-center rounded-md bg-accent px-3.5 text-label font-[510] text-fg-on-accent transition-colors hover:bg-accent-hover"
              >
                Start in test mode
              </Link>
            </div>
            <div className="rounded-xl bg-surface p-5 ring-hairline">
              <p className="font-mono text-caption tracking-[0.08em] text-fg-subtle uppercase">
                Developer
              </p>
              <p className="mt-2.5 text-body-lg font-[510] text-fg">API reference</p>
              <p className="mt-1.5 text-label text-fg-subtle">
                Every endpoint with its scope, the payment lifecycle, idempotency rules, webhook
                signing, the sandbox, and agentic policies. Jump in below.
              </p>
              <a
                href="#reference"
                className="mt-4 inline-flex h-9 items-center rounded-md bg-surface-elevated px-3.5 text-label font-[510] text-fg ring-hairline hover:bg-surface-active"
              >
                Jump to the reference
              </a>
            </div>
          </div>

          <div className="mt-4 grid gap-3 grid-cols-1 sm:grid-cols-3">
            {[
              {
                title: 'Quickstart',
                body: 'Key → payment → authorize → capture → verify.',
                href: '#payments',
              },
              { title: 'Webhooks', body: 'Signed events instead of polling.', href: '#webhooks' },
              {
                title: 'Sandbox',
                body: '17 test instruments with fixed outcomes.',
                href: '#sandbox',
              },
            ].map((c) => (
              <a key={c.title} href={c.href} className="block">
                <Card interactive className="h-full">
                  <CardContent className="pt-4">
                    <p className="text-label font-[510] text-fg">{c.title}</p>
                    <p className="mt-1 text-label-sm text-fg-subtle">{c.body}</p>
                  </CardContent>
                </Card>
              </a>
            ))}
          </div>
        </section>

        <section id="authentication" className="scroll-mt-24 border-t border-border-subtle pt-10">
          <SectionTitle>Authentication</SectionTitle>
          <p className="mt-2.5 max-w-[64ch] text-body text-fg-subtle">
            Every request carries a secret key in the{' '}
            <code className="font-mono text-fg-muted">Authorization</code> header. The key’s prefix
            determines the mode — a test key cannot touch live data. Mode is a property of the
            credential, never a request parameter.
          </p>
          <div className="mt-5 grid gap-3 grid-cols-1 sm:grid-cols-3">
            {[
              [
                'sk_test_••••',
                'Test mode. Unmetered, never expires, cannot reach live data. No real money moves.',
                'warning' as const,
              ],
              [
                'sk_live_••••',
                'Live mode. The current platform still uses a simulated acquirer, so no real funds move in either mode. Shown once at creation; rotate rather than reissue.',
                'neutral' as const,
              ],
              [
                'Authorization: Bearer',
                'Scopes are attached to the key. A route rejects a key that lacks its scope with 403.',
                'info' as const,
              ],
            ].map(([prefix, body, tone]) => (
              <Card key={prefix as string}>
                <CardContent className="pt-5">
                  <p className="font-mono text-label-sm break-words text-fg">
                    <Badge tone={tone as 'warning'} dot className="align-middle">
                      {prefix}
                    </Badge>
                  </p>
                  <p className="mt-2 text-label text-fg-subtle">{body}</p>
                </CardContent>
              </Card>
            ))}
          </div>
        </section>

        <section id="payments" className="scroll-mt-24 border-t border-border-subtle pt-10">
          <SectionTitle>Payments</SectionTitle>
          <p className="mt-2.5 max-w-[64ch] text-body text-fg-subtle">
            Create a payment, then move it through explicit transitions. The transition table is
            enforced server-side: a capture against a payment that was never authorized returns 409
            rather than silently succeeding.
          </p>
          <div className="mt-5 flex flex-wrap items-center gap-2">
            <Badge tone="info">POST</Badge>
            <span className="font-mono text-label text-fg">/v1/payments</span>
            <span className="font-mono text-label-sm text-fg-subtle">payments:write</span>
          </div>
          <div className="mt-3 grid grid-cols-1 gap-3 lg:grid-cols-2">
            <CodeBlock samples={[{ label: 'Request', code: REQ }]} />
            <CodeBlock samples={[{ label: 'Response · 201', code: RES }]} />
          </div>
          <Card className="mt-3">
            <CardContent className="overflow-x-auto p-0">
              <table className="w-full min-w-[34rem] border-collapse text-left">
                <thead>
                  <tr className="border-b border-border-subtle">
                    {['Field', 'Type', 'Required', 'Notes'].map((h) => (
                      <th
                        key={h}
                        className="px-4 py-2.5 text-caption font-[510] tracking-[0.04em] text-fg-subtle uppercase"
                      >
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {PARAMS.map(([field, type, req, note]) => (
                    <tr
                      key={field}
                      className="border-b border-border-subtle last:border-0 align-top"
                    >
                      <td className="px-4 py-3 font-mono text-label-sm text-fg">{field}</td>
                      <td className="px-4 py-3 font-mono text-label-sm text-fg-subtle">{type}</td>
                      <td
                        className={`px-4 py-3 text-label-sm ${req === 'required' ? 'text-fg' : 'text-fg-subtle'}`}
                      >
                        {req}
                      </td>
                      <td className="px-4 py-3 text-label-sm text-fg-subtle">{note}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </CardContent>
          </Card>
        </section>

        <section id="idempotency" className="scroll-mt-24 border-t border-border-subtle pt-10">
          <SectionTitle>Idempotency</SectionTitle>
          <p className="mt-2.5 max-w-[64ch] text-body text-fg-subtle">
            Every mutation requires an{' '}
            <code className="font-mono text-fg-muted">Idempotency-Key</code> header. One key belongs
            to one logical attempt and is reused on every retry of that attempt — a fresh key is a
            fresh payment, which is how double charges happen.
          </p>
          <div className="mt-5 grid gap-3 grid-cols-1 sm:grid-cols-3">
            {[
              [
                'SAME KEY, SAME BODY',
                'The original response is replayed. Nothing executes a second time.',
                'success' as const,
              ],
              [
                'SAME KEY, DIFFERENT BODY',
                '409 idempotency_key_reuse. The mismatch is reported, not resolved.',
                'danger' as const,
              ],
              [
                'CONCURRENT SAME KEY',
                'The second request waits on the lock, then receives the first one’s result.',
                'neutral' as const,
              ],
            ].map(([tag, body, tone]) => (
              <Card key={tag as string}>
                <CardContent className="pt-5">
                  <Badge tone={tone as 'success'}>
                    <span className="font-mono text-caption">{tag}</span>
                  </Badge>
                  <p className="mt-2 text-label text-fg-subtle">{body}</p>
                </CardContent>
              </Card>
            ))}
          </div>
        </section>

        <section id="reference" className="scroll-mt-24 border-t border-border-subtle pt-10">
          <SectionTitle>API reference</SectionTitle>
          <p className="mt-2.5 max-w-[64ch] text-body text-fg-subtle">
            Grouped by resource, with the scope each route requires. Generated from the contract.
          </p>
          <div className="mt-5 grid gap-4 grid-cols-1 md:grid-cols-2">
            {GROUPS.map((g) => (
              <Card key={g.name}>
                <CardContent className="p-0">
                  <div className="flex items-center justify-between gap-2 border-b border-border-subtle px-4 py-3">
                    <span className="text-label font-[510] text-fg">{g.name}</span>
                    <span className="font-mono text-label-sm text-fg-subtle">{g.scope}</span>
                  </div>
                  <ul className="divide-y divide-border-subtle">
                    {g.rows.map(([method, path]) => (
                      <li key={path} className="flex items-center gap-3 px-4 py-2.5">
                        <span className="w-14 shrink-0">
                          <Badge tone={METHOD_TONE[method] ?? 'neutral'}>
                            <span className="font-mono text-caption">{method}</span>
                          </Badge>
                        </span>
                        <span className="min-w-0 font-mono text-label-sm break-words text-fg-muted">
                          {path}
                        </span>
                      </li>
                    ))}
                  </ul>
                </CardContent>
              </Card>
            ))}
          </div>
        </section>

        <section id="webhooks" className="scroll-mt-24 border-t border-border-subtle pt-10">
          <SectionTitle>Webhooks and events</SectionTitle>
          <div className="mt-4 grid gap-4 grid-cols-1 lg:grid-cols-2">
            <Card>
              <CardContent className="pt-5">
                <h3 className="text-label font-[510] text-fg">Status vocabulary</h3>
                <p className="mt-0.5 text-label-sm text-fg-subtle">
                  A filter value outside this set returns a 400 naming the vocabulary.
                </p>
                <div className="mt-3 flex flex-wrap gap-2">
                  {[
                    'created',
                    'authorized',
                    'captured',
                    'partially_refunded',
                    'refunded',
                    'failed',
                    'voided',
                  ].map((s) => (
                    <StatusPill key={s} status={s} family="payment" dot />
                  ))}
                </div>
                <p className="mt-4 font-mono text-caption tracking-[0.08em] text-fg-subtle uppercase">
                  Event types
                </p>
                <p className="mt-2 font-mono text-label-sm break-words text-fg-muted">
                  {EVENT_TYPES.join(' · ')}
                </p>
              </CardContent>
            </Card>
            <div>
              <CodeBlock samples={[{ label: 'Verify a signature', code: HOOK }]} />
              <p className="mt-2 text-label-sm text-fg-subtle">
                Verify against the raw body. Parsing and re-serializing changes the bytes and the
                signature will not match.
              </p>
            </div>
          </div>
        </section>

        <section id="guides" className="scroll-mt-24 border-t border-border-subtle pt-10">
          <SectionTitle>Guides</SectionTitle>
          <p className="mt-2.5 text-body text-fg-subtle">The five that prevent most incidents.</p>
          <Card className="mt-4">
            <CardContent className="divide-y divide-border-subtle p-0">
              {GUIDES.map(([title, body]) => (
                <div key={title} className="flex gap-3 px-4 py-3">
                  <span aria-hidden className="mt-1.5 size-1.5 shrink-0 rounded-full bg-accent" />
                  <div>
                    <p className="text-label text-fg">{title}</p>
                    <p className="text-label-sm text-fg-subtle">{body}</p>
                  </div>
                </div>
              ))}
            </CardContent>
          </Card>
        </section>

        <section id="agentic" className="scroll-mt-24 border-t border-border-subtle pt-10">
          <SectionTitle>Agentic commerce and policies</SectionTitle>
          <p className="mt-2.5 max-w-[64ch] text-body text-fg-subtle">
            Agents call typed tools; the policy engine decides. Rules are deterministic and
            versioned, and hard caps are evaluated before approval thresholds so an amount past the
            outer bound is never offered to a person to wave through.
          </p>
          <div className="mt-5 grid gap-3 grid-cols-1 sm:grid-cols-3">
            {POLICY_BANDS.map((p) => (
              <Card key={p.outcome}>
                <CardContent className="pt-5">
                  <Badge tone={p.tone}>
                    <span className="font-mono text-caption">{p.outcome}</span>
                  </Badge>
                  <p className="tabular mt-2 text-label text-fg-muted">{p.band}</p>
                  <p className="text-label-sm text-fg-subtle">{p.note}</p>
                </CardContent>
              </Card>
            ))}
          </div>
          <div className="mt-4 flex flex-wrap gap-2">
            <Link
              href="/agentic-commerce"
              className="inline-flex h-9 items-center rounded-md bg-surface-elevated px-3.5 text-label font-[510] text-fg ring-hairline hover:bg-surface-active"
            >
              Agentic commerce overview
            </Link>
          </div>
        </section>

        <section id="sandbox" className="scroll-mt-24 border-t border-border-subtle pt-10">
          <SectionTitle>SDKs and sandbox</SectionTitle>
          <div className="mt-4 grid gap-4 grid-cols-1 lg:grid-cols-2">
            <CodeBlock
              samples={[
                { label: 'Node.js', code: NODE },
                { label: 'Python', code: PY },
              ]}
            />
            <div className="rounded-lg border border-mode-test-border bg-mode-test-surface p-5">
              <Badge tone="test" dot>
                Test mode
              </Badge>
              <h3 className="mt-3 text-label font-[510] text-fg">Sandbox</h3>
              <p className="mt-2 text-label text-fg-subtle">
                Seventeen instruments with fixed outcomes, plus simulation overrides for declines,
                error codes and latency. A demo decision is labelled as such and never presented as
                a cardholder authorization.
              </p>
            </div>
          </div>
          <div className="mt-4 flex flex-wrap items-center gap-3 rounded-lg border border-mode-test-border bg-mode-test-surface p-4">
            <span aria-hidden className="size-1.5 shrink-0 rounded-full bg-mode-test" />
            <p className="min-w-0 flex-1 basis-64 text-label text-fg-muted">
              Both SDK packages are currently unpublished. Install commands on this page are
              illustrative until they reach a public registry — use the local install instructions
              in the repository meanwhile.
            </p>
            <Link
              href="/contact"
              className="inline-flex h-9 items-center rounded-md bg-surface-elevated px-3.5 text-label font-[510] text-fg ring-hairline hover:bg-surface-active"
            >
              Ask about access
            </Link>
          </div>
        </section>
      </main>
    </div>
  );
}

function SectionTitle({ children }: { children: React.ReactNode }) {
  return (
    <h2 className="text-[1.5rem] leading-tight font-[510] tracking-[-0.02em] text-fg sm:text-[1.875rem]">
      {children}
    </h2>
  );
}
