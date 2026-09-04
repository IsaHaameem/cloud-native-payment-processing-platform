'use client';

import { AlertCircle, Check } from 'lucide-react';
import * as React from 'react';
import { useActionState } from 'react';
import { useFormStatus } from 'react-dom';

import {
  DataTable,
  DataTableBody,
  DataTableCell,
  DataTableHead,
  DataTableRow,
} from '@/components/patterns/data-table';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { CopyButton } from '@/components/ui/copy-button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { useToast } from '@/components/ui/toast';
import type { SimulationOverrideResponse, TestCardResponse } from '@/generated/models';
import { CSRF_FIELD } from '@/lib/security/csrf-field';
import { useInvalidatePlatform, usePlatformQuery } from '@/lib/query/use-platform';

import { SANDBOX_IDLE } from './action-state';
import { createSimulationOverrideAction, revokeSimulationOverrideAction } from './actions';

const SCENARIOS = [
  'FORCE_DECLINE',
  'FORCE_ERROR',
  'INJECT_LATENCY',
  'FORCE_TIMEOUT',
  'FORCE_RATE_LIMIT',
  'DELAY_SETTLEMENT',
  'DUPLICATE_WEBHOOKS',
  'WEBHOOK_FAILURE',
];

const OUTCOME_TONE: Record<string, 'success' | 'danger' | 'warning' | 'neutral'> = {
  APPROVE: 'success',
  APPROVED: 'success',
  DECLINE: 'danger',
  DECLINED: 'danger',
  ERROR: 'danger',
  TIMEOUT: 'warning',
};

export function SandboxClient({ csrfToken }: { csrfToken: string }) {
  const cards = usePlatformQuery<
    TestCardResponse[] | { data?: TestCardResponse[]; content?: TestCardResponse[] }
  >('listTestCards');
  const override = usePlatformQuery<SimulationOverrideResponse | null>(
    'getActiveSimulationOverride',
  );
  const invalidate = useInvalidatePlatform();
  const refresh = () => void invalidate.scope();

  const cardList = Array.isArray(cards.data)
    ? cards.data
    : (cards.data?.data ?? cards.data?.content ?? []);

  return (
    <div className="flex flex-col gap-6">
      <ActiveOverride
        override={override.data}
        loading={override.isPending}
        csrfToken={csrfToken}
        onChange={refresh}
      />

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1.4fr_1fr]">
        <Card>
          <CardContent className="p-0">
            <div className="border-b border-border-subtle px-4 py-3">
              <h2 className="text-label font-[510] text-fg">Test instruments</h2>
              <p className="text-label-sm text-fg-subtle">
                Each token has a fixed outcome, decline code and capture behaviour.
              </p>
            </div>
            {cards.isPending ? (
              <div className="space-y-2 p-4">
                {Array.from({ length: 6 }, (_, i) => (
                  <Skeleton key={i} className="h-8 w-full" />
                ))}
              </div>
            ) : cards.isError ? (
              <p className="p-4 text-label text-danger">{cards.error.message}</p>
            ) : (
              <div className="overflow-x-auto">
                <DataTable className="ring-0">
                  <DataTableHead
                    columns={['Token', 'Brand', 'Outcome', 'Capture', 'Decline code']}
                  />
                  <DataTableBody>
                    {cardList.map((c) => (
                      <DataTableRow key={c.token ?? Math.random()}>
                        <DataTableCell className="font-mono text-label-sm text-fg">
                          <span className="flex items-center gap-1.5">
                            {c.token ?? '—'}
                            {c.token ? <CopyButton value={c.token} className="size-5" /> : null}
                          </span>
                        </DataTableCell>
                        <DataTableCell className="text-label-sm">{c.brand ?? '—'}</DataTableCell>
                        <DataTableCell>
                          <Badge tone={OUTCOME_TONE[(c.outcome ?? '').toUpperCase()] ?? 'neutral'}>
                            {c.outcome ?? '—'}
                          </Badge>
                        </DataTableCell>
                        <DataTableCell className="text-label-sm text-fg-subtle">
                          {c.captureBehaviour ?? '—'}
                        </DataTableCell>
                        <DataTableCell className="font-mono text-label-sm text-fg-subtle">
                          {c.declineCode ?? '—'}
                        </DataTableCell>
                      </DataTableRow>
                    ))}
                  </DataTableBody>
                </DataTable>
              </div>
            )}
          </CardContent>
        </Card>

        <CreateOverride csrfToken={csrfToken} onChange={refresh} />
      </div>
    </div>
  );
}

function ActiveOverride({
  override,
  loading,
  csrfToken,
  onChange,
}: {
  override: SimulationOverrideResponse | null | undefined;
  loading: boolean;
  csrfToken: string;
  onChange: () => void;
}) {
  const [state, formAction] = useActionState(revokeSimulationOverrideAction, SANDBOX_IDLE);
  const { toast } = useToast();
  const seen = React.useRef(state);
  React.useEffect(() => {
    if (state === seen.current) return;
    seen.current = state;
    if (state.ok) {
      toast({ tone: 'success', title: 'Simulation override revoked' });
      onChange();
    } else if (state.error) {
      toast({ tone: 'danger', title: 'Could not revoke the override', description: state.error });
    }
  }, [state, onChange, toast]);

  if (loading) return <Skeleton className="h-20 rounded-lg" />;

  if (!override || !override.scenario) {
    return (
      <div className="rounded-lg bg-surface p-4 text-label text-fg-subtle ring-hairline">
        No simulation override is active. The mode&rsquo;s default behaviour applies.
      </div>
    );
  }

  return (
    <div className="rounded-lg border border-mode-test-border bg-mode-test-surface p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <Badge tone="test" dot>
              Override active
            </Badge>
            <span className="font-mono text-label text-fg">{override.scenario}</span>
          </div>
          <p className="mt-2 font-mono text-label-sm text-fg-muted">
            {[
              override.declineCode ? `decline_code=${override.declineCode}` : null,
              override.errorCode ? `error_code=${override.errorCode}` : null,
              override.latencyMs != null ? `latency=${override.latencyMs}ms` : null,
              override.remainingCount != null ? `${override.remainingCount} left` : null,
            ]
              .filter(Boolean)
              .join(' · ') || 'no parameters'}
          </p>
        </div>
        <form action={formAction}>
          <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
          <RevokeButton />
        </form>
      </div>
      {state.error ? <p className="mt-2 text-label-sm text-danger">{state.error}</p> : null}
    </div>
  );
}

function CreateOverride({ csrfToken, onChange }: { csrfToken: string; onChange: () => void }) {
  const [state, formAction] = useActionState(createSimulationOverrideAction, SANDBOX_IDLE);
  const { toast } = useToast();
  const seen = React.useRef(state);
  React.useEffect(() => {
    if (state === seen.current) return;
    seen.current = state;
    if (state.ok) {
      toast({ tone: 'success', title: 'Simulation override set' });
      onChange();
    } else if (state.error) {
      toast({ tone: 'danger', title: 'Could not set the override', description: state.error });
    }
  }, [state, onChange, toast]);

  return (
    <Card>
      <CardContent className="pt-4">
        <h2 className="mb-1 text-label font-[510] text-fg">Set an override</h2>
        <p className="mb-3 text-label-sm text-fg-subtle">
          Applies to subsequent payments until it expires or you revoke it.
        </p>
        <form action={formAction} className="space-y-3">
          <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
          <label className="block space-y-1.5">
            <span className="text-label text-fg-muted">Scenario</span>
            <select
              name="scenario"
              defaultValue="FORCE_DECLINE"
              className="h-8 w-full rounded-md bg-surface-inset px-2.5 text-body text-fg ring-hairline"
            >
              {SCENARIOS.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>
          <div className="grid grid-cols-2 gap-2">
            <label className="space-y-1.5">
              <span className="text-label-sm text-fg-muted">Decline code</span>
              <Input name="declineCode" placeholder="insufficient_funds" />
            </label>
            <label className="space-y-1.5">
              <span className="text-label-sm text-fg-muted">Latency (ms)</span>
              <Input name="latencyMs" inputMode="numeric" placeholder="0" />
            </label>
            <label className="space-y-1.5">
              <span className="text-label-sm text-fg-muted">Remaining count</span>
              <Input name="remainingCount" inputMode="numeric" placeholder="unlimited" />
            </label>
            <label className="space-y-1.5">
              <span className="text-label-sm text-fg-muted">Duration (s)</span>
              <Input name="durationSeconds" inputMode="numeric" placeholder="none" />
            </label>
          </div>
          {state.error ? (
            <p role="alert" className="flex items-start gap-1.5 text-label-sm text-danger">
              <AlertCircle className="mt-px size-3.5 shrink-0" aria-hidden />
              {state.error}
            </p>
          ) : state.ok ? (
            <p className="inline-flex items-center gap-1 text-label-sm text-success">
              <Check className="size-3.5" /> Override set
            </p>
          ) : null}
          <ApplyButton />
        </form>
      </CardContent>
    </Card>
  );
}

function RevokeButton() {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" variant="secondary" size="sm" disabled={pending}>
      {pending ? 'Revoking…' : 'Revoke'}
    </Button>
  );
}

function ApplyButton() {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" variant="primary" size="md" disabled={pending} className="w-full">
      {pending ? 'Applying…' : 'Apply override'}
    </Button>
  );
}
