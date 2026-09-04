'use client';

import { useRouter } from 'next/navigation';
import * as React from 'react';
import { useActionState } from 'react';
import { useFormStatus } from 'react-dom';

import { StatusPill } from '@/components/patterns/status-pill';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { useToast } from '@/components/ui/toast';
import { type AgenticTurn } from '@/lib/agentic/types';
import { CSRF_FIELD } from '@/lib/security/csrf-field';

import { APPROVAL_IDLE } from '../action-state';
import { approveApprovalAction, denyApprovalAction } from '../actions';

/**
 * The decision controls on a pending approval (M-agentic).
 *
 * Two guarded Server Actions. Approve is destructive in the sense that matters — it executes the
 * money action — so it takes a confirm dialog naming what will run. Deny takes an optional
 * reason. Both re-check policy server-side; this component never decides anything, it only
 * submits and renders the outcome.
 */
export function ApprovalDecision({
  approvalId,
  csrfToken,
}: {
  approvalId: string;
  csrfToken: string;
}) {
  const [approveState, setApproveTurn] = React.useState<AgenticTurn | undefined>(undefined);
  const [approveError, setApproveError] = React.useState<
    { message: string; requestId: string | undefined } | undefined
  >(undefined);

  return (
    <div className="space-y-4">
      <Card>
        <CardContent className="flex flex-wrap items-center gap-3 py-4">
          <p className="mr-auto text-label-sm text-fg-subtle">
            Approving runs the action now. The agentic service re-derives every bound field and
            refuses on the first that moved since the request.
          </p>
          <DenyButton approvalId={approvalId} csrfToken={csrfToken} />
          <ApproveButton
            approvalId={approvalId}
            csrfToken={csrfToken}
            onTurn={setApproveTurn}
            onError={setApproveError}
          />
        </CardContent>
      </Card>

      {approveError ? (
        <Card>
          <CardContent className="py-3 text-label-sm text-danger">
            {approveError.message}
            {approveError.requestId ? (
              <span className="ml-2 font-mono text-caption text-fg-subtle">
                request {approveError.requestId}
              </span>
            ) : null}
          </CardContent>
        </Card>
      ) : null}

      {approveState ? <TurnOutcome turn={approveState} /> : null}
    </div>
  );
}

function ApproveButton({
  approvalId,
  csrfToken,
  onTurn,
  onError,
}: {
  approvalId: string;
  csrfToken: string;
  onTurn: (turn: AgenticTurn) => void;
  onError: (error: { message: string; requestId: string | undefined }) => void;
}) {
  const [open, setOpen] = React.useState(false);
  const [state, formAction] = useActionState(approveApprovalAction, APPROVAL_IDLE);
  const router = useRouter();
  const { toast } = useToast();
  const seen = React.useRef(state);

  React.useEffect(() => {
    if (state === seen.current) return;
    seen.current = state;
    if (state.done === 'approved') {
      setOpen(false);
      if (state.turn) onTurn(state.turn);
      toast({ title: 'Approval granted', tone: 'success' });
      router.refresh();
    } else if (state.error) {
      setOpen(false);
      onError({ message: state.error, requestId: state.requestId });
      toast({ title: 'The approval did not go through', description: state.error, tone: 'danger' });
    }
  }, [state, onTurn, onError, router, toast]);

  return (
    <>
      <Button variant="primary" onClick={() => setOpen(true)}>
        Approve
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent open={open}>
          <DialogHeader>
            <DialogTitle>Approve and execute?</DialogTitle>
            <DialogDescription>
              This grants the approval and runs the money action in the same request. It cannot be
              undone from here.
            </DialogDescription>
          </DialogHeader>
          <form action={formAction} className="mt-2">
            <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
            <input type="hidden" name="approvalId" value={approvalId} />
            <DialogFooter className="mt-4">
              <DialogClose asChild>
                <Button type="button" variant="ghost">
                  Cancel
                </Button>
              </DialogClose>
              <SubmitButton pendingLabel="Approving…" variant="primary">
                Approve and execute
              </SubmitButton>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

function DenyButton({ approvalId, csrfToken }: { approvalId: string; csrfToken: string }) {
  const [open, setOpen] = React.useState(false);
  const [state, formAction] = useActionState(denyApprovalAction, APPROVAL_IDLE);
  const router = useRouter();
  const { toast } = useToast();
  const seen = React.useRef(state);

  React.useEffect(() => {
    if (state === seen.current) return;
    seen.current = state;
    if (state.done === 'denied') {
      setOpen(false);
      toast({ title: 'Approval denied', tone: 'success' });
      router.refresh();
    } else if (state.error) {
      toast({ title: 'The denial did not go through', description: state.error, tone: 'danger' });
    }
  }, [state, router, toast]);

  return (
    <>
      <Button variant="secondary" onClick={() => setOpen(true)}>
        Deny
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent open={open}>
          <DialogHeader>
            <DialogTitle>Deny this approval?</DialogTitle>
            <DialogDescription>
              Terminal. Nothing financial happens now or later under this approval. The agent is
              told it was denied.
            </DialogDescription>
          </DialogHeader>
          <form action={formAction} className="mt-2">
            <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
            <input type="hidden" name="approvalId" value={approvalId} />
            <label className="block space-y-1.5">
              <span className="text-label-sm text-fg-subtle">Reason (optional)</span>
              <textarea
                name="reason"
                rows={3}
                maxLength={500}
                className="w-full rounded-md bg-surface-inset px-3 py-2 text-label text-fg-muted ring-hairline outline-none focus:ring-2 focus:ring-accent-ring"
                placeholder="Shown to the agent and recorded on the trail."
              />
            </label>
            <DialogFooter className="mt-4">
              <DialogClose asChild>
                <Button type="button" variant="ghost">
                  Cancel
                </Button>
              </DialogClose>
              <SubmitButton pendingLabel="Denying…" variant="secondary">
                Deny approval
              </SubmitButton>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

function TurnOutcome({ turn }: { turn: AgenticTurn }) {
  return (
    <Card>
      <CardContent className="space-y-3 pt-4">
        <div className="flex items-center gap-2">
          <h3 className="text-label font-[510] text-fg">Execution result</h3>
          <span className="font-mono text-caption text-fg-subtle">{turn.stopReason}</span>
        </div>
        {turn.reply ? <p className="text-label-sm text-fg-subtle">{turn.reply}</p> : null}
        {turn.actions.length > 0 ? (
          <ul className="divide-y divide-border-subtle">
            {turn.actions.map((action, i) => (
              <li key={action.actionId ?? i} className="flex flex-wrap items-center gap-2 py-2">
                <span className="min-w-[10rem] font-mono text-label-sm text-fg-muted">
                  {action.toolName}
                </span>
                <StatusPill status={action.state} family="action" />
                {action.policyDecision ? (
                  <StatusPill status={action.policyDecision} family="policy" />
                ) : null}
                {!action.ok && action.message ? (
                  <span className="text-label-sm text-danger">{action.message}</span>
                ) : null}
              </li>
            ))}
          </ul>
        ) : null}
      </CardContent>
    </Card>
  );
}

function SubmitButton({
  children,
  pendingLabel,
  variant,
}: {
  children: React.ReactNode;
  pendingLabel: string;
  variant: 'primary' | 'secondary';
}) {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" variant={variant} disabled={pending}>
      {pending ? pendingLabel : children}
    </Button>
  );
}
