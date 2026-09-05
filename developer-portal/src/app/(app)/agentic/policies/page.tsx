import type { Metadata } from 'next';

import { ErrorState } from '@/components/patterns/error-state';
import { PageHeader } from '@/components/patterns/page-header';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { loadAgentic } from '@/lib/agentic/load';
import { getAgenticConfig } from '@/lib/agentic/operations';
import { formatMoney } from '@/lib/format';
import { requireMerchant } from '@/lib/session/require';

export const metadata: Metadata = { title: 'Policies' };

export const dynamic = 'force-dynamic';

/**
 * The policy engine, as a read-only reference — live (M-agentic, G-3).
 *
 * `GET /api/agentic/config` returns the rules **derived from the engine's own `PolicyRule` enum
 * and the configured thresholds**, not a transcription. A number shown here is the number the
 * runtime enforces; there is no second copy to drift.
 */
export default async function PoliciesPage() {
  const session = await requireMerchant();
  const result = await loadAgentic(() => getAgenticConfig(session));

  if (result.error) {
    return (
      <div>
        <PageHeader
          title="Policies"
          description="The deterministic gate every agent action passes."
        />
        <ErrorState
          title="The policy could not be loaded"
          description={result.error.message}
          code={result.error.code}
          requestId={result.error.requestId}
        />
      </div>
    );
  }

  const { policy } = result.data;

  return (
    <div>
      <PageHeader
        title="Policies"
        description="The deterministic gate every agent action passes before anything financial happens. The agent proposes; this decides."
        actions={
          <span className="font-mono text-caption text-fg-subtle">
            {policy.version} · {policy.currency}
          </span>
        }
      />

      <Card>
        <CardContent className="overflow-x-auto p-0">
          <table className="w-full text-left text-label-sm">
            <thead>
              <tr className="border-b border-border-subtle text-caption uppercase text-fg-subtle">
                {['Rule', 'Phase', 'Decision', 'Scope', 'Threshold', ''].map((h) => (
                  <th key={h} className="px-4 py-2.5 font-[510]">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {policy.rules.map((rule) => (
                <tr key={rule.id} className="border-b border-border-subtle last:border-0 align-top">
                  <td className="px-4 py-3">
                    <span className="font-mono text-fg-muted">{rule.id}</span>
                    <p className="mt-0.5 text-caption text-fg-subtle">{rule.description}</p>
                  </td>
                  <td className="px-4 py-3 text-caption text-fg-subtle">
                    {rule.phase.replace(/_/g, ' ').toLowerCase()}
                  </td>
                  <td className="px-4 py-3">
                    <Badge
                      tone={
                        rule.decision === 'PERMIT'
                          ? 'success'
                          : rule.decision === 'REQUIRES_APPROVAL'
                            ? 'warning'
                            : 'danger'
                      }
                    >
                      {rule.decision.replace(/_/g, ' ').toLowerCase()}
                    </Badge>
                    {rule.waivable ? (
                      <span className="ml-2 text-caption text-fg-subtle">human-waivable</span>
                    ) : null}
                  </td>
                  <td className="px-4 py-3 text-caption text-fg-subtle">{rule.scope ?? '—'}</td>
                  <td className="px-4 py-3 font-mono text-fg-muted">
                    {rule.threshold === null
                      ? '—'
                      : rule.thresholdUnit === 'MINOR'
                        ? formatMoney(rule.threshold, policy.currency)
                        : String(rule.threshold)}
                  </td>
                  <td className="px-4 py-3">
                    {rule.disabled ? <Badge tone="neutral">operation disabled</Badge> : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>

      <p className="mt-3 text-caption text-fg-subtle">
        Declaration order, not evaluation order. Hard caps are evaluated before the approval
        threshold — an amount beyond a cap is refused outright, never offered to a human. A
        non-positive threshold disables its operation rather than unbounding it.
      </p>
    </div>
  );
}
