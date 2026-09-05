import type { Metadata } from 'next';

import { DetailList } from '@/components/patterns/detail-list';
import { ErrorState } from '@/components/patterns/error-state';
import { PageHeader } from '@/components/patterns/page-header';
import { Badge } from '@/components/ui/badge';
import { RiskBadge, type RiskClass } from '@/components/ui/risk-badge';
import { Card, CardContent } from '@/components/ui/card';
import { loadAgentic } from '@/lib/agentic/load';
import { getAgenticConfig } from '@/lib/agentic/operations';
import { readCsrfToken } from '@/lib/security/csrf';
import { requireMerchant } from '@/lib/session/require';

import { AgentConsole } from './console';

export const metadata: Metadata = { title: 'Agent' };

/**
 * Agent configuration (read-only, live) plus the dev-only "Try it" console (M-agentic).
 *
 * The configuration now comes from `GET /api/agentic/config` (G-3) — the values the runtime
 * actually uses, with credentials reported only as booleans. The console drives the live pipeline
 * through the signed proxy in test mode.
 */
export const dynamic = 'force-dynamic';

const RISK_BY_CATEGORY: Record<string, RiskClass> = {
  READ: 'READ',
  COMMERCE: 'COMMERCE',
  PAYMENT: 'PAYMENT',
  REFUND: 'REFUND',
};

export default async function AgentConfigPage() {
  const session = await requireMerchant();
  const [csrfToken, config] = await Promise.all([
    readCsrfToken(),
    loadAgentic(() => getAgenticConfig(session)),
  ]);

  return (
    <div>
      <PageHeader
        title="Agent"
        description="How the agent runtime is configured, and a console to exercise it. Configuration is read-only; the console runs the real pipeline in test mode."
      />

      <div className="mb-4">
        <AgentConsole csrfToken={csrfToken} />
      </div>

      {config.error ? (
        <ErrorState
          title="Configuration could not be loaded"
          description={config.error.message}
          code={config.error.code}
          requestId={config.error.requestId}
        />
      ) : (
        <>
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <Card>
              <CardContent className="pt-4">
                <h2 className="mb-3 text-label font-[510] text-fg">Runtime</h2>
                <DetailList
                  rows={[
                    { label: 'Mode', value: config.data.mode, mono: true },
                    { label: 'Prompt version', value: config.data.promptVersion, mono: true },
                    {
                      label: 'LLM provider',
                      value: (
                        <span className="flex items-center gap-2">
                          <span className="font-mono">{config.data.llm.provider}</span>
                          {config.data.llm.scriptedFallback ? (
                            <Badge tone="warning">scripted fallback</Badge>
                          ) : (
                            <Badge tone="success">credential set</Badge>
                          )}
                        </span>
                      ),
                    },
                    { label: 'Model', value: config.data.llm.model, mono: true },
                    {
                      label: 'Max tool iterations',
                      value: String(config.data.llm.maxToolIterations),
                      mono: true,
                    },
                    {
                      label: 'Max turn duration',
                      value: `${config.data.llm.maxTurnDurationMs} ms`,
                      mono: true,
                    },
                    {
                      label: 'Checkout TTL',
                      value: `${config.data.checkout.ttlMinutes} minutes`,
                      mono: true,
                    },
                    {
                      label: 'Max line items',
                      value: String(config.data.checkout.maxLineItems),
                      mono: true,
                    },
                    {
                      label: 'Provider (Razorpay)',
                      value: config.data.razorpay.credentialConfigured
                        ? 'configured'
                        : 'not configured',
                    },
                    {
                      label: 'Uncollected-order outcome',
                      value: config.data.razorpay.uncollectedOrderOutcome,
                      mono: true,
                    },
                  ]}
                />
              </CardContent>
            </Card>

            <Card>
              <CardContent className="pt-4">
                <h2 className="mb-2 text-label font-[510] text-fg">
                  What is stored, and what is not
                </h2>
                <p className="text-label text-fg-subtle">
                  Agent input summaries, failure messages and tool results are redacted{' '}
                  <span className="font-[510] text-fg-muted">before</span> they are written. The
                  action log stores a redacted, canonical input summary — never a raw request or raw
                  model output.
                </p>
                <p className="mt-3 text-label text-fg-subtle">
                  The model never sees a credential: not a tool parameter, not a tool return field,
                  not present in any string it receives. This screen reports credentials only as
                  &ldquo;configured / not&rdquo;.
                </p>
              </CardContent>
            </Card>
          </div>

          <Card className="mt-4">
            <CardContent className="pt-4">
              <h2 className="mb-1 text-label font-[510] text-fg">Tools the agent may call</h2>
              <p className="mb-3 text-label-sm text-fg-subtle">
                The complete allow-list, from the running service. Risk class governs which policy
                rules apply.
              </p>
              <ul className="divide-y divide-border-subtle">
                {config.data.tools.map((tool) => (
                  <li key={tool.name} className="flex flex-wrap items-center gap-3 py-2.5">
                    <span className="min-w-[11rem] font-mono text-label-sm text-fg-muted">
                      {tool.name}
                    </span>
                    <RiskBadge risk={RISK_BY_CATEGORY[tool.category] ?? 'READ'} />
                    <span className="min-w-[14rem] flex-1 text-label-sm text-fg-subtle">
                      {tool.description}
                    </span>
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}
