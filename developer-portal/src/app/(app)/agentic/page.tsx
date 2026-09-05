import Link from 'next/link';
import type { Metadata } from 'next';

import { ErrorState } from '@/components/patterns/error-state';
import { PageHeader } from '@/components/patterns/page-header';
import { Badge } from '@/components/ui/badge';
import { RiskBadge, type RiskClass } from '@/components/ui/risk-badge';
import { Card, CardContent } from '@/components/ui/card';
import { loadAgentic } from '@/lib/agentic/load';
import { getAgenticConfig, getAgenticSummary } from '@/lib/agentic/operations';
import { requireMerchant } from '@/lib/session/require';

export const metadata: Metadata = { title: 'Agentic Commerce' };

/**
 * The agentic commerce overview — live (M-agentic).
 *
 * Every panel is now backed by a real endpoint: `GET /api/agentic/summary` (G-1) for the
 * activity counts, `GET /api/agentic/config` (G-3) for the runtime status and tool inventory,
 * both through the portal's signed server-side proxy. The counts are persisted data, not
 * Prometheus meters, and `agentInitiated` payments say nothing about whether each was a real
 * cardholder authorisation — that is a per-payment fact on the provider decision (G-6).
 */
export const dynamic = 'force-dynamic';

const RISK_BY_CATEGORY: Record<string, RiskClass> = {
  READ: 'READ',
  COMMERCE: 'COMMERCE',
  PAYMENT: 'PAYMENT',
  REFUND: 'REFUND',
};

export default async function AgenticOverviewPage() {
  const session = await requireMerchant();
  const [summary, config] = await Promise.all([
    loadAgentic(() => getAgenticSummary(session)),
    loadAgentic(() => getAgenticConfig(session)),
  ]);

  return (
    <div>
      <PageHeader
        title="Agentic Commerce"
        description="Give your AI agent the ability to discover products, create checkouts and request payments — while PaymentFlow enforces your policies. The agent proposes; the platform decides."
      />

      <Card className="mb-6">
        <CardContent className="pt-5">
          <p className="text-label font-[510] text-fg">Basic setup</p>
          <ol className="mt-3 grid gap-2.5 text-label text-fg-subtle sm:grid-cols-2 lg:grid-cols-3">
            {[
              ['Connect your merchant', 'One server-side API key the agent acts under.'],
              [
                'Configure your policy',
                'Amount caps and the approval threshold. Sensible defaults ship.',
              ],
              [
                'Give the agent a prompt',
                'Generate one on the AI integration page (tick “Agentic commerce”).',
              ],
              ['Test the agent', 'Ask it to find a product and buy it. Watch the action trace.'],
              ['Review actions', 'Every tool call, policy decision and payment is recorded.'],
              ['Approve sensitive actions', 'Anything past your threshold waits for a person.'],
            ].map(([title, body], i) => (
              <li key={title} className="flex gap-2.5">
                <span className="flex size-5 shrink-0 items-center justify-center rounded-full bg-surface-active text-caption font-[510] text-fg-subtle">
                  {i + 1}
                </span>
                <span>
                  <span className="block font-[510] text-fg">{title}</span>
                  <span className="block text-label-sm">{body}</span>
                </span>
              </li>
            ))}
          </ol>
          <div className="mt-4 flex flex-wrap gap-2 text-label-sm">
            <Link
              href="/developers/ai"
              className="inline-flex h-8 items-center rounded-md bg-accent px-3 font-[510] text-fg-on-accent hover:bg-accent-hover"
            >
              Generate an agent prompt
            </Link>
            <Link
              href="/agentic/policies"
              className="inline-flex h-8 items-center rounded-md bg-surface-elevated px-3 font-[510] text-fg ring-hairline hover:bg-surface-active"
            >
              Advanced controls — policies
            </Link>
            <Link
              href="/agentic/agent"
              className="inline-flex h-8 items-center rounded-md bg-surface-elevated px-3 font-[510] text-fg ring-hairline hover:bg-surface-active"
            >
              Agent configuration
            </Link>
          </div>
        </CardContent>
      </Card>

      {summary.error ? (
        <ErrorState
          title="Activity could not be loaded"
          description={summary.error.message}
          code={summary.error.code}
          requestId={summary.error.requestId}
        />
      ) : (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          {[
            {
              label: 'Conversations',
              value: summary.data.conversations.total,
              href: '/agentic/conversations',
            },
            { label: 'Actions', value: summary.data.actions.total, href: '/agentic/actions' },
            { label: 'Executed', value: summary.data.actions.executed, href: '/agentic/actions' },
            {
              label: 'Policy refusals',
              value: summary.data.policyDecisions.refuse,
              href: '/agentic/actions',
            },
            {
              label: 'Approvals pending',
              value: summary.data.approvals.pending,
              href: '/agentic/approvals',
            },
            {
              label: 'Agent payments',
              value: summary.data.payments.agentInitiated,
              href: '/agentic/actions',
            },
          ].map((stat) => (
            <Link key={stat.label} href={stat.href}>
              <Card className="h-full transition-colors hover:bg-surface-hover">
                <CardContent className="pt-4">
                  <p className="text-caption uppercase text-fg-subtle">{stat.label}</p>
                  <p className="mt-1 font-mono text-body-lg text-fg">{stat.value}</p>
                </CardContent>
              </Card>
            </Link>
          ))}
        </div>
      )}

      <Card className="mt-4">
        <CardContent className="pt-4">
          <h2 className="mb-3 text-label font-[510] text-fg">Agent status</h2>
          {config.error ? (
            <ErrorState
              title="Configuration could not be loaded"
              description={config.error.message}
              code={config.error.code}
              requestId={config.error.requestId}
            />
          ) : (
            <div className="flex flex-wrap items-center gap-x-6 gap-y-2 text-label-sm text-fg-muted">
              <span>
                mode <span className="font-mono">{config.data.mode}</span>
              </span>
              <span>
                provider <span className="font-mono">{config.data.llm.provider}</span>
              </span>
              <span>
                model <span className="font-mono">{config.data.llm.model}</span>
              </span>
              <span>
                prompt <span className="font-mono">{config.data.promptVersion}</span>
              </span>
              {config.data.llm.scriptedFallback ? (
                <Badge tone="warning">scripted fallback — no model credential</Badge>
              ) : (
                <Badge tone="success">model credential configured</Badge>
              )}
              <Link href="/agentic/policies" className="text-accent-text hover:underline">
                Policy ({config.data.policy.rules.length} rules) →
              </Link>
            </div>
          )}
        </CardContent>
      </Card>

      <Card className="mt-4">
        <CardContent className="pt-4">
          <h2 className="mb-1 text-label font-[510] text-fg">Tool registry</h2>
          <p className="mb-3 text-label-sm text-fg-subtle">
            The complete allow-list, from the running service. There is no generic HTTP, shell or
            SQL tool, and one cannot be added by accident.
          </p>
          {config.error ? null : (
            <ul className="divide-y divide-border-subtle">
              {config.data.tools.map((tool) => (
                <li key={tool.name} className="flex flex-wrap items-center gap-3 py-2.5">
                  <span className="min-w-[12rem] flex-1 font-mono text-label-sm text-fg-muted">
                    {tool.name}
                  </span>
                  <RiskBadge risk={RISK_BY_CATEGORY[tool.category] ?? 'READ'} />
                  <span className="min-w-[16rem] flex-1 text-label-sm text-fg-subtle">
                    {tool.description}
                  </span>
                  <span className="text-caption text-fg-subtle">
                    {tool.movesMoney ? 'moves money' : 'no money movement'}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
