'use client';

import { AlertCircle, Check, Plus, RotateCw, Webhook } from 'lucide-react';
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
import { EmptyState } from '@/components/patterns/empty-state';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { CopyButton } from '@/components/ui/copy-button';
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Drawer } from '@/components/ui/drawer';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs } from '@/components/ui/tabs';
import { useToast } from '@/components/ui/toast';
import type { WebhookDeliveryResponse, WebhookEndpointResponse } from '@/generated/models';
import { formatDateTime, truncateId } from '@/lib/format';
import { CSRF_FIELD } from '@/lib/security/csrf-field';
import { useInvalidatePlatform, usePlatformQuery } from '@/lib/query/use-platform';

import { WEBHOOK_IDLE, type WebhookActionState } from './action-state';
import {
  createWebhookEndpointAction,
  deleteWebhookEndpointAction,
  replayWebhookDeliveryAction,
  rotateWebhookSecretAction,
  setWebhookEnabledAction,
} from './actions';

const DONE_TITLE: Record<string, string> = {
  created: 'Endpoint created',
  rotated: 'Signing secret rotated',
  updated: 'Endpoint updated',
  deleted: 'Endpoint deleted',
  replayed: 'Delivery replay queued',
};

/** Announces a webhook action's outcome once per completed result. */
function useWebhookNotify(state: WebhookActionState) {
  const { toast } = useToast();
  const seen = React.useRef<WebhookActionState>(state);
  React.useEffect(() => {
    if (state === seen.current) return;
    seen.current = state;
    if (state.ok && state.done) {
      toast({ tone: 'success', title: DONE_TITLE[state.done] ?? 'Done' });
    } else if (state.error) {
      toast({ tone: 'danger', title: 'That did not work', description: state.error });
    }
  }, [state, toast]);
}

const EVENT_TYPES = [
  'payment.created',
  'payment.authorized',
  'payment.captured',
  'payment.failed',
  'payment.refunded',
  'payment.partially_refunded',
  'payment.voided',
];

const DELIVERY_TONE: Record<string, 'success' | 'warning' | 'danger' | 'neutral'> = {
  delivered: 'success',
  pending: 'warning',
  retrying: 'warning',
  failed: 'danger',
};

export function WebhooksClient({ csrfToken }: { csrfToken: string }) {
  const [tab, setTab] = React.useState<'endpoints' | 'deliveries'>('endpoints');

  return (
    <div className="flex flex-col gap-4">
      <Tabs
        aria-label="Webhooks"
        value={tab}
        onValueChange={(v) => setTab(v as 'endpoints' | 'deliveries')}
        items={[
          { id: 'endpoints', label: 'Endpoints' },
          { id: 'deliveries', label: 'Deliveries' },
        ]}
      />
      {tab === 'endpoints' ? (
        <Endpoints csrfToken={csrfToken} />
      ) : (
        <Deliveries csrfToken={csrfToken} />
      )}
    </div>
  );
}

/* ── Endpoints ─────────────────────────────────────────────────────────────────────── */

function Endpoints({ csrfToken }: { csrfToken: string }) {
  const query = usePlatformQuery<WebhookEndpointResponse[] | { data?: WebhookEndpointResponse[] }>(
    'listWebhookEndpoints',
  );
  const list = Array.isArray(query.data) ? query.data : (query.data?.data ?? []);
  const invalidate = useInvalidatePlatform();
  const refetch = () => void invalidate.operation('listWebhookEndpoints');

  const failing = list.filter((e) => (e.consecutiveFailureCount ?? 0) > 0 || e.disabledAt);

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap gap-2 text-label-sm text-fg-subtle">
          <span>
            <span className="tabular text-fg">{list.length}</span> endpoint
            {list.length === 1 ? '' : 's'}
          </span>
          {failing.length > 0 ? (
            <Badge tone="danger" dot>
              {failing.length} need attention
            </Badge>
          ) : null}
        </div>
        <CreateEndpointDialog csrfToken={csrfToken} onDone={refetch} />
      </div>

      {query.isError ? (
        <Err message={query.error.message} onRetry={() => void query.refetch()} />
      ) : query.isPending ? (
        <div className="space-y-2">
          {[0, 1].map((i) => (
            <Skeleton key={i} className="h-20 rounded-lg" />
          ))}
        </div>
      ) : list.length === 0 ? (
        <EmptyState
          icon={<Webhook className="size-5 text-accent" aria-hidden />}
          title="No webhook endpoints"
          description="Register an https:// URL and choose the events it should receive."
        />
      ) : (
        <ul className="space-y-3">
          {list.map((e) => (
            <li key={e.id}>
              <EndpointCard endpoint={e} csrfToken={csrfToken} onDone={refetch} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function EndpointCard({
  endpoint: e,
  csrfToken,
  onDone,
}: {
  endpoint: WebhookEndpointResponse;
  csrfToken: string;
  onDone: () => void;
}) {
  const disabled = e.enabled === false;
  return (
    <Card>
      <CardContent className="pt-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="font-mono text-label break-all text-fg">{e.url}</p>
            <div className="mt-1.5 flex flex-wrap items-center gap-2 text-label-sm text-fg-subtle">
              {disabled ? (
                <Badge tone="danger" dot>
                  {e.disabledReason ? `Disabled — ${e.disabledReason}` : 'Disabled'}
                </Badge>
              ) : (
                <Badge tone="success" dot>
                  Enabled
                </Badge>
              )}
              <span>
                {(e.enabledEvents ?? []).length} event
                {(e.enabledEvents ?? []).length === 1 ? '' : 's'}
              </span>
              {(e.consecutiveFailureCount ?? 0) > 0 ? (
                <span className="text-danger">
                  {e.consecutiveFailureCount} consecutive failures
                </span>
              ) : null}
              {e.signingSecretPrefix ? (
                <span className="font-mono">{e.signingSecretPrefix}…</span>
              ) : null}
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            <SecretRotateButton id={e.id ?? ''} csrfToken={csrfToken} onDone={onDone} />
            <ToggleEnabledButton
              id={e.id ?? ''}
              enabled={e.enabled !== false}
              csrfToken={csrfToken}
              onDone={onDone}
            />
            <DeleteEndpointButton id={e.id ?? ''} csrfToken={csrfToken} onDone={onDone} />
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

function CreateEndpointDialog({ csrfToken, onDone }: { csrfToken: string; onDone: () => void }) {
  const [open, setOpen] = React.useState(false);
  const [state, formAction] = useActionState(createWebhookEndpointAction, WEBHOOK_IDLE);
  useWebhookNotify(state);

  React.useEffect(() => {
    if (state.ok && state.done === 'created') onDone();
  }, [state.ok, state.done, onDone]);

  // Keep the dialog open while a secret is on screen; close it once the user acknowledges.
  const showSecret = state.ok && state.secret;

  return (
    <>
      <Button variant="primary" size="md" onClick={() => setOpen(true)}>
        <Plus /> Add endpoint
      </Button>
      <Dialog open={open} onOpenChange={(o) => (o ? setOpen(true) : setOpen(false))}>
        <DialogContent open={open}>
          {showSecret ? (
            <>
              <DialogHeader>
                <DialogTitle>Copy your signing secret now</DialogTitle>
                <DialogDescription>
                  This is the only time it is shown. Afterwards only its prefix is displayed — the
                  platform stores no reversible copy.
                </DialogDescription>
              </DialogHeader>
              <div className="mt-3 flex items-center gap-2 rounded-md bg-surface-inset p-3 ring-hairline">
                <code className="min-w-0 flex-1 font-mono text-label break-all text-fg select-all">
                  {state.secret}
                </code>
                <CopyButton value={state.secret ?? ''} />
              </div>
              <DialogFooter className="mt-4">
                <Button variant="primary" size="md" onClick={() => setOpen(false)}>
                  I have copied it
                </Button>
              </DialogFooter>
            </>
          ) : (
            <>
              <DialogHeader>
                <DialogTitle>Add a webhook endpoint</DialogTitle>
                <DialogDescription>
                  The URL cannot be changed later — it is half an endpoint&rsquo;s identity.
                </DialogDescription>
              </DialogHeader>
              <form action={formAction} className="mt-3 space-y-3">
                <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
                <label className="block space-y-1.5">
                  <span className="text-label text-fg-muted">Endpoint URL</span>
                  <Input
                    name="url"
                    placeholder="https://api.example.com/hooks/paymentflow"
                    required
                  />
                </label>
                <label className="block space-y-1.5">
                  <span className="text-label text-fg-muted">Description (optional)</span>
                  <Input name="description" maxLength={200} placeholder="Production receiver" />
                </label>
                <fieldset className="space-y-2">
                  <legend className="text-label text-fg-muted">Events</legend>
                  <div className="grid grid-cols-1 gap-1.5 sm:grid-cols-2">
                    {EVENT_TYPES.map((t) => (
                      <label
                        key={t}
                        className="flex items-center gap-2 text-label-sm text-fg-muted"
                      >
                        <input type="checkbox" name="events" value={t} defaultChecked />
                        <span className="font-mono">{t}</span>
                      </label>
                    ))}
                  </div>
                </fieldset>
                {state.error ? (
                  <p role="alert" className="flex items-start gap-1.5 text-label-sm text-danger">
                    <AlertCircle className="mt-px size-3.5 shrink-0" aria-hidden />
                    {state.error}
                  </p>
                ) : null}
                <DialogFooter className="mt-4">
                  <DialogClose asChild>
                    <Button variant="ghost" size="md" type="button">
                      Cancel
                    </Button>
                  </DialogClose>
                  <SubmitButton label="Create endpoint" pendingLabel="Creating…" />
                </DialogFooter>
              </form>
            </>
          )}
        </DialogContent>
      </Dialog>
    </>
  );
}

function SecretRotateButton({
  id,
  csrfToken,
  onDone,
}: {
  id: string;
  csrfToken: string;
  onDone: () => void;
}) {
  const [open, setOpen] = React.useState(false);
  const [state, formAction] = useActionState(rotateWebhookSecretAction, WEBHOOK_IDLE);
  useWebhookNotify(state);

  React.useEffect(() => {
    if (state.ok) onDone();
  }, [state.ok, onDone]);

  return (
    <>
      <Button variant="secondary" size="sm" onClick={() => setOpen(true)}>
        Rotate secret
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent open={open}>
          {state.ok && state.secret ? (
            <>
              <DialogHeader>
                <DialogTitle>Copy the new signing secret now</DialogTitle>
                <DialogDescription>
                  The previous secret keeps working for a short grace window so deploys overlap
                  cleanly. This value is shown only once.
                </DialogDescription>
              </DialogHeader>
              <div className="mt-3 flex items-center gap-2 rounded-md bg-surface-inset p-3 ring-hairline">
                <code className="min-w-0 flex-1 font-mono text-label break-all text-fg select-all">
                  {state.secret}
                </code>
                <CopyButton value={state.secret} />
              </div>
              <DialogFooter className="mt-4">
                <Button variant="primary" size="md" onClick={() => setOpen(false)}>
                  I have copied it
                </Button>
              </DialogFooter>
            </>
          ) : (
            <>
              <DialogHeader>
                <DialogTitle>Rotate this endpoint&rsquo;s secret?</DialogTitle>
                <DialogDescription>
                  A new secret is issued and shown once. The old one stays valid for its grace
                  window.
                </DialogDescription>
              </DialogHeader>
              <form action={formAction} className="mt-2">
                <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
                <input type="hidden" name="id" value={id} />
                {state.error ? <p className="text-label-sm text-danger">{state.error}</p> : null}
                <DialogFooter className="mt-4">
                  <DialogClose asChild>
                    <Button variant="ghost" size="md" type="button">
                      Cancel
                    </Button>
                  </DialogClose>
                  <SubmitButton label="Rotate secret" pendingLabel="Rotating…" />
                </DialogFooter>
              </form>
            </>
          )}
        </DialogContent>
      </Dialog>
    </>
  );
}

function ToggleEnabledButton({
  id,
  enabled,
  csrfToken,
  onDone,
}: {
  id: string;
  enabled: boolean;
  csrfToken: string;
  onDone: () => void;
}) {
  const [state, formAction] = useActionState(setWebhookEnabledAction, WEBHOOK_IDLE);
  useWebhookNotify(state);
  React.useEffect(() => {
    if (state.ok) onDone();
  }, [state.ok, onDone]);
  return (
    <form action={formAction} className="contents">
      <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
      <input type="hidden" name="id" value={id} />
      <input type="hidden" name="enabled" value={enabled ? 'false' : 'true'} />
      <SubmitButton
        variant="secondary"
        label={enabled ? 'Disable' : 'Enable'}
        pendingLabel="Saving…"
      />
    </form>
  );
}

function DeleteEndpointButton({
  id,
  csrfToken,
  onDone,
}: {
  id: string;
  csrfToken: string;
  onDone: () => void;
}) {
  const [open, setOpen] = React.useState(false);
  const [state, formAction] = useActionState(deleteWebhookEndpointAction, WEBHOOK_IDLE);
  useWebhookNotify(state);
  React.useEffect(() => {
    if (state.ok) {
      setOpen(false);
      onDone();
    }
  }, [state.ok, onDone]);
  return (
    <>
      <Button variant="ghost" size="sm" onClick={() => setOpen(true)}>
        Delete
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent open={open}>
          <DialogHeader>
            <DialogTitle>Delete this endpoint?</DialogTitle>
            <DialogDescription>
              Its delivery history is retained, but no further deliveries are sent. This cannot be
              undone.
            </DialogDescription>
          </DialogHeader>
          <form action={formAction} className="mt-2">
            <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
            <input type="hidden" name="id" value={id} />
            {state.error ? <p className="text-label-sm text-danger">{state.error}</p> : null}
            <DialogFooter className="mt-4">
              <DialogClose asChild>
                <Button variant="ghost" size="md" type="button">
                  Cancel
                </Button>
              </DialogClose>
              <SubmitButton variant="danger" label="Delete endpoint" pendingLabel="Deleting…" />
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

/* ── Deliveries ────────────────────────────────────────────────────────────────────── */

function Deliveries({ csrfToken }: { csrfToken: string }) {
  const [page, setPage] = React.useState(0);
  const [openDelivery, setOpenDelivery] = React.useState<WebhookDeliveryResponse | null>(null);
  const query = usePlatformQuery<{
    content?: WebhookDeliveryResponse[];
    totalPages?: number;
    last?: boolean;
  }>('listWebhookDeliveries', { page, size: 25 });

  const rows = query.data?.content ?? [];
  const invalidate = useInvalidatePlatform();

  return (
    <div className="flex flex-col gap-4">
      {query.isError ? (
        <Err message={query.error.message} onRetry={() => void query.refetch()} />
      ) : query.isPending ? (
        <Skeleton className="h-64 w-full rounded-lg" />
      ) : rows.length === 0 ? (
        <EmptyState
          title="No deliveries yet"
          description="Deliveries appear here as events fire."
        />
      ) : (
        <>
          <DataTable>
            <DataTableHead columns={['Delivery', 'Event', 'Status', 'Attempts', 'Last attempt']} />
            <DataTableBody>
              {rows.map((d) => (
                <DataTableRow
                  key={d.id ?? Math.random()}
                  role="button"
                  tabIndex={0}
                  className="cursor-pointer"
                  onClick={() => setOpenDelivery(d)}
                  onKeyDown={(e: React.KeyboardEvent) => {
                    if (e.key === 'Enter') setOpenDelivery(d);
                  }}
                >
                  <DataTableCell className="font-mono text-label-sm text-fg">
                    {d.id ? truncateId(d.id) : '—'}
                  </DataTableCell>
                  <DataTableCell className="font-mono text-label-sm text-fg-muted">
                    {d.eventType ?? '—'}
                  </DataTableCell>
                  <DataTableCell>
                    <Badge tone={DELIVERY_TONE[(d.status ?? '').toLowerCase()] ?? 'neutral'} dot>
                      {d.status ?? '—'}
                    </Badge>
                  </DataTableCell>
                  <DataTableCell className="tabular text-right text-label-sm">
                    {d.attemptCount ?? 0}
                  </DataTableCell>
                  <DataTableCell className="whitespace-nowrap text-label-sm">
                    {d.lastAttemptedAt ? formatDateTime(d.lastAttemptedAt) : '—'}
                  </DataTableCell>
                </DataTableRow>
              ))}
            </DataTableBody>
          </DataTable>

          <div className="flex items-center justify-between">
            <Button
              variant="secondary"
              size="sm"
              disabled={page === 0}
              onClick={() => setPage((p) => p - 1)}
            >
              Previous
            </Button>
            <span className="text-label-sm text-fg-subtle">
              Page {page + 1}
              {query.data?.totalPages ? ` of ${query.data.totalPages}` : ''}
            </span>
            <Button
              variant="secondary"
              size="sm"
              disabled={query.data?.last === true}
              onClick={() => setPage((p) => p + 1)}
            >
              Next
            </Button>
          </div>
        </>
      )}

      <Drawer
        open={openDelivery !== null}
        onOpenChange={(o) => !o && setOpenDelivery(null)}
        title={openDelivery?.eventType ?? 'Delivery'}
        description={openDelivery?.id}
      >
        {openDelivery ? (
          <div className="space-y-4">
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-label-sm">
              {(
                [
                  ['Status', openDelivery.status ?? '—'],
                  ['Attempts', String(openDelivery.attemptCount ?? 0)],
                  ['Event id', openDelivery.eventId ?? '—'],
                  ['Endpoint', openDelivery.endpointId ?? '—'],
                  ['Next attempt', openDelivery.nextAttemptAt ?? '—'],
                  ['Replayed from', openDelivery.replayedFromDeliveryId ?? '—'],
                ] as const
              ).map(([k, v]) => (
                <div key={k}>
                  <dt className="text-fg-subtle">{k}</dt>
                  <dd className="font-mono break-all text-fg-muted">{v}</dd>
                </div>
              ))}
            </dl>

            <div>
              <p className="mb-1.5 text-label-sm text-fg-subtle">Attempts</p>
              <ul className="space-y-1.5">
                {(openDelivery.attempts ?? []).map((att, i) => (
                  <li
                    key={i}
                    className="flex flex-wrap items-center gap-3 rounded-md bg-surface-inset px-3 py-2 text-label-sm ring-hairline"
                  >
                    <span className="font-mono text-fg-subtle">#{i + 1}</span>
                    <span className="font-mono text-fg-muted">
                      {JSON.stringify(att).slice(0, 120)}
                    </span>
                  </li>
                ))}
                {(openDelivery.attempts ?? []).length === 0 ? (
                  <li className="text-label-sm text-fg-subtle">No attempts recorded.</li>
                ) : null}
              </ul>
            </div>

            {openDelivery.id ? (
              <ReplayButton
                id={openDelivery.id}
                csrfToken={csrfToken}
                onDone={() => void invalidate.operation('listWebhookDeliveries')}
              />
            ) : null}
          </div>
        ) : null}
      </Drawer>
    </div>
  );
}

function ReplayButton({
  id,
  csrfToken,
  onDone,
}: {
  id: string;
  csrfToken: string;
  onDone: () => void;
}) {
  const [state, formAction] = useActionState(replayWebhookDeliveryAction, WEBHOOK_IDLE);
  useWebhookNotify(state);
  React.useEffect(() => {
    if (state.ok) onDone();
  }, [state.ok, onDone]);
  return (
    <form action={formAction}>
      <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
      <input type="hidden" name="id" value={id} />
      {state.ok ? (
        <p className="mb-2 inline-flex items-center gap-1 text-label-sm text-success">
          <Check className="size-3.5" /> Replay queued
        </p>
      ) : state.error ? (
        <p className="mb-2 text-label-sm text-danger">{state.error}</p>
      ) : null}
      <SubmitButton variant="secondary" label="Replay this delivery" pendingLabel="Queuing…" />
    </form>
  );
}

/* ── Shared ────────────────────────────────────────────────────────────────────────── */

function SubmitButton({
  label,
  pendingLabel,
  variant = 'primary',
}: {
  label: string;
  pendingLabel: string;
  variant?: 'primary' | 'secondary' | 'danger';
}) {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" variant={variant} size="sm" disabled={pending}>
      {pending ? pendingLabel : label}
    </Button>
  );
}

function Err({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div className="flex items-start gap-3 rounded-lg bg-surface p-5 ring-hairline">
      <AlertCircle className="mt-0.5 size-4 shrink-0 text-danger" aria-hidden />
      <div className="min-w-0 flex-1">
        <p className="text-label font-[510] text-fg">Something went wrong</p>
        <p className="mt-1 text-label text-fg-subtle">{message}</p>
        <Button variant="secondary" size="sm" className="mt-4" onClick={onRetry}>
          <RotateCw /> Try again
        </Button>
      </div>
    </div>
  );
}
