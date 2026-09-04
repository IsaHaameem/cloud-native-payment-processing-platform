'use client';

import { AlertCircle, CheckCircle2 } from 'lucide-react';
import * as React from 'react';
import { useActionState } from 'react';
import { useFormStatus } from 'react-dom';

import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { useToast } from '@/components/ui/toast';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { formatMoney } from '@/lib/format';
import { CSRF_FIELD } from '@/lib/security/csrf-field';
import { useInvalidatePlatform } from '@/lib/query/use-platform';
import { cn } from '@/lib/utils';

import { IDLE, type PaymentActionState } from '../action-state';
import {
  authorizePaymentAction,
  capturePaymentAction,
  refundPaymentAction,
  voidPaymentAction,
} from '../actions';

/**
 * The FSM action row on a payment (frontend build).
 *
 * `frontend_Design.md §13.2`: the four transitions, each enabled only from the states its
 * `PaymentStatus` row permits. A blocked action stays visible with a tooltip naming the state
 * that blocks it, rather than vanishing. Refund and void take a confirm dialog; authorize and
 * capture run directly. On success the TanStack cache is invalidated so the header, timeline and
 * panels re-read.
 */

interface Ctx {
  id: string;
  csrfToken: string;
  status: string;
  currency: string;
  refundableMinor: number;
}

export function PaymentActions(ctx: Ctx) {
  const status = ctx.status.toLowerCase();
  const canAuthorize = status === 'created';
  const canCapture = status === 'authorized';
  const canVoid = status === 'authorized';
  const canRefund = status === 'captured' || status === 'partially_refunded';

  return (
    <div className="flex flex-wrap items-center gap-2">
      <DirectAction
        ctx={ctx}
        action={authorizePaymentAction}
        enabled={canAuthorize}
        label="Authorize"
        pendingLabel="Authorizing…"
        blockedReason="Only a payment in the created state can be authorized."
        variant="secondary"
      />
      <DirectAction
        ctx={ctx}
        action={capturePaymentAction}
        enabled={canCapture}
        label="Capture"
        pendingLabel="Capturing…"
        blockedReason="Only an authorized payment can be captured."
        variant="primary"
      />
      <ConfirmAction
        ctx={ctx}
        action={voidPaymentAction}
        enabled={canVoid}
        label="Void"
        variant="secondary"
        blockedReason="A payment can only be voided while it is authorized and before capture."
        title="Void this authorization?"
        description="This releases the held funds without capturing them. It is terminal — the payment cannot be authorized again."
        confirmLabel="Void authorization"
      />
      <RefundAction ctx={ctx} enabled={canRefund} />
    </div>
  );
}

/* ── Direct (no dialog): authorize, capture ─────────────────────────────────────────── */

function DirectAction({
  ctx,
  action,
  enabled,
  label,
  pendingLabel,
  blockedReason,
  variant,
}: {
  ctx: Ctx;
  action: (s: PaymentActionState, f: FormData) => Promise<PaymentActionState>;
  enabled: boolean;
  label: string;
  pendingLabel: string;
  blockedReason: string;
  variant: 'primary' | 'secondary';
}) {
  const [state, formAction] = useActionState(action, IDLE);
  const invalidate = useInvalidatePlatform();
  const key = React.useMemo(() => crypto.randomUUID(), []);
  useResultToast(state, `${label} succeeded`, `${label} failed`);

  React.useEffect(() => {
    if (state.ok) void invalidate.scope();
  }, [state.ok, invalidate]);

  if (!enabled) return <BlockedButton label={label} reason={blockedReason} />;

  return (
    <form action={formAction} className="contents">
      <input type="hidden" name={CSRF_FIELD} value={ctx.csrfToken} />
      <input type="hidden" name="id" value={ctx.id} />
      <input type="hidden" name="idempotencyKey" value={key} />
      <SubmitButton variant={variant} label={label} pendingLabel={pendingLabel} />
      <ActionResult state={state} />
    </form>
  );
}

/* ── Confirm dialog: void ───────────────────────────────────────────────────────────── */

function ConfirmAction({
  ctx,
  action,
  enabled,
  label,
  variant,
  blockedReason,
  title,
  description,
  confirmLabel,
}: {
  ctx: Ctx;
  action: (s: PaymentActionState, f: FormData) => Promise<PaymentActionState>;
  enabled: boolean;
  label: string;
  variant: 'primary' | 'secondary';
  blockedReason: string;
  title: string;
  description: string;
  confirmLabel: string;
}) {
  const [open, setOpen] = React.useState(false);
  const [state, formAction] = useActionState(action, IDLE);
  const invalidate = useInvalidatePlatform();
  const key = React.useMemo(() => (open ? crypto.randomUUID() : ''), [open]);
  useResultToast(state, `${label} succeeded`, `${label} failed`);

  React.useEffect(() => {
    if (state.ok) {
      setOpen(false);
      void invalidate.scope();
    }
  }, [state.ok, invalidate]);

  if (!enabled) return <BlockedButton label={label} reason={blockedReason} />;

  return (
    <>
      <Button variant={variant} size="md" onClick={() => setOpen(true)}>
        {label}
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent open={open}>
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
            <DialogDescription>{description}</DialogDescription>
          </DialogHeader>
          <form action={formAction} className="mt-2">
            <input type="hidden" name={CSRF_FIELD} value={ctx.csrfToken} />
            <input type="hidden" name="id" value={ctx.id} />
            <input type="hidden" name="idempotencyKey" value={key} />
            {state.error ? <InlineError state={state} /> : null}
            <DialogFooter className="mt-4">
              <DialogClose asChild>
                <Button variant="ghost" size="md" type="button">
                  Cancel
                </Button>
              </DialogClose>
              <SubmitButton variant="danger" label={confirmLabel} pendingLabel="Working…" />
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

/* ── Refund dialog (amount + reason) ────────────────────────────────────────────────── */

function RefundAction({ ctx, enabled }: { ctx: Ctx; enabled: boolean }) {
  const [open, setOpen] = React.useState(false);
  const [full, setFull] = React.useState(true);
  const [state, formAction] = useActionState(refundPaymentAction, IDLE);
  const invalidate = useInvalidatePlatform();
  const key = React.useMemo(() => (open ? crypto.randomUUID() : ''), [open]);
  useResultToast(state, 'Refund issued', 'Refund failed');

  React.useEffect(() => {
    if (state.ok) {
      setOpen(false);
      void invalidate.scope();
    }
  }, [state.ok, invalidate]);

  if (!enabled) {
    return (
      <BlockedButton
        label="Refund"
        reason="A payment must be captured or partially refunded before it can be refunded."
      />
    );
  }

  return (
    <>
      <Button variant="secondary" size="md" onClick={() => setOpen(true)}>
        Refund
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent open={open}>
          <DialogHeader>
            <DialogTitle>Refund this payment</DialogTitle>
            <DialogDescription>
              Up to {formatMoney(ctx.refundableMinor, ctx.currency)} remains refundable. A refund
              posts to the ledger and emits an event.
            </DialogDescription>
          </DialogHeader>
          <form action={formAction} className="mt-3 space-y-3">
            <input type="hidden" name={CSRF_FIELD} value={ctx.csrfToken} />
            <input type="hidden" name="id" value={ctx.id} />
            <input type="hidden" name="idempotencyKey" value={key} />

            <div className="flex gap-2">
              {(['full', 'partial'] as const).map((mode) => (
                <button
                  key={mode}
                  type="button"
                  onClick={() => setFull(mode === 'full')}
                  aria-pressed={full === (mode === 'full')}
                  className={cn(
                    'h-8 rounded-md px-3 text-label-sm font-[510] ring-1 ring-inset transition-colors',
                    full === (mode === 'full')
                      ? 'bg-surface-active text-fg ring-border-strong'
                      : 'text-fg-subtle ring-border hover:text-fg',
                  )}
                >
                  {mode === 'full' ? 'Full remaining' : 'Partial'}
                </button>
              ))}
            </div>

            {!full ? (
              <label className="block space-y-1.5">
                <span className="text-label text-fg-muted">
                  Amount in minor units ({ctx.currency})
                </span>
                <Input
                  name="amountMinor"
                  inputMode="numeric"
                  pattern="[0-9]*"
                  placeholder={String(ctx.refundableMinor)}
                  required
                />
              </label>
            ) : null}

            <label className="block space-y-1.5">
              <span className="text-label text-fg-muted">Reason (optional, for your records)</span>
              <Input name="reason" maxLength={500} placeholder="Customer request" />
            </label>

            {state.error ? <InlineError state={state} /> : null}

            <DialogFooter className="mt-4">
              <DialogClose asChild>
                <Button variant="ghost" size="md" type="button">
                  Cancel
                </Button>
              </DialogClose>
              <SubmitButton variant="primary" label="Issue refund" pendingLabel="Refunding…" />
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

/* ── Shared bits ────────────────────────────────────────────────────────────────────── */

/**
 * Fires a toast once per completed action result. The action state object changes identity on
 * every dispatch, so a ref tracks the last one that was announced.
 */
function useResultToast(state: PaymentActionState, successTitle: string, failureTitle: string) {
  const { toast } = useToast();
  const seen = React.useRef<PaymentActionState>(state);
  React.useEffect(() => {
    if (state === seen.current) return;
    seen.current = state;
    if (state.ok) {
      toast({ tone: 'success', title: successTitle });
    } else if (state.error) {
      toast({
        tone: 'danger',
        title: failureTitle,
        description: state.requestId ? `${state.error} · ${state.requestId}` : state.error,
      });
    }
  }, [state, toast, successTitle, failureTitle]);
}

function SubmitButton({
  variant,
  label,
  pendingLabel,
}: {
  variant: 'primary' | 'secondary' | 'danger';
  label: string;
  pendingLabel: string;
}) {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" variant={variant} size="md" disabled={pending}>
      {pending ? pendingLabel : label}
    </Button>
  );
}

function BlockedButton({ label, reason }: { label: string; reason: string }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span>
          <Button variant="secondary" size="md" disabled>
            {label}
          </Button>
        </span>
      </TooltipTrigger>
      <TooltipContent side="bottom">{reason}</TooltipContent>
    </Tooltip>
  );
}

function ActionResult({ state }: { state: PaymentActionState }) {
  if (state.ok) {
    return (
      <span className="inline-flex items-center gap-1 text-label-sm text-success">
        <CheckCircle2 className="size-3.5" aria-hidden /> Done
      </span>
    );
  }
  if (state.error) return <InlineError state={state} />;
  return null;
}

function InlineError({ state }: { state: PaymentActionState }) {
  return (
    <p role="alert" className="flex items-start gap-1.5 text-label-sm text-danger">
      <AlertCircle className="mt-px size-3.5 shrink-0" aria-hidden />
      <span>
        {state.error}
        {state.code ? <span className="ml-1 font-mono text-fg-subtle">({state.code})</span> : null}
        {state.requestId ? (
          <span className="mt-0.5 block font-mono text-fg-subtle select-all">
            {state.requestId}
          </span>
        ) : null}
      </span>
    </p>
  );
}
