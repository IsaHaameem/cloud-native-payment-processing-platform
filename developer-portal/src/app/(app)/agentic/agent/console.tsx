'use client';

import Link from 'next/link';
import * as React from 'react';
import { useActionState } from 'react';
import { useFormStatus } from 'react-dom';

import { StatusPill } from '@/components/patterns/status-pill';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { useToast } from '@/components/ui/toast';
import { type AgenticTurn } from '@/lib/agentic/types';
import { CSRF_FIELD } from '@/lib/security/csrf-field';

import { AGENT_CONSOLE_IDLE } from './action-state';
import { sendAgentMessageAction } from './actions';

/**
 * The dev-only agent console (M-agentic).
 *
 * `frontend_Design.md` is explicit that the portal is not a chat client — the buyer talks to the
 * agent elsewhere. This panel exists so an operator can drive the whole pipeline end to end in
 * **test mode** and watch what it does: message → model → tool → policy → approval → platform →
 * trail. It calls the same `POST /api/agentic/conversations/{id}/messages` every caller does,
 * through the signed proxy, with no shortcut.
 *
 * It keeps a local transcript of the turns it has sent this session; the durable record is the
 * Conversation screen it links to.
 */

interface Exchange {
  readonly sent: string;
  readonly turn: AgenticTurn;
}

export function AgentConsole({ csrfToken }: { csrfToken: string }) {
  const [state, formAction] = useActionState(sendAgentMessageAction, AGENT_CONSOLE_IDLE);
  const [exchanges, setExchanges] = React.useState<readonly Exchange[]>([]);
  const [conversationId, setConversationId] = React.useState<string | undefined>(undefined);
  const formRef = React.useRef<HTMLFormElement>(null);
  const seen = React.useRef(state);
  const { toast } = useToast();

  React.useEffect(() => {
    if (state === seen.current) return;
    seen.current = state;

    if (state.ok && state.turn && state.sentMessage !== undefined) {
      setExchanges((current) => [...current, { sent: state.sentMessage!, turn: state.turn! }]);
      setConversationId(state.conversationId);
      formRef.current?.reset();
      if (state.turn.stopReason === 'APPROVAL_REQUIRED') {
        toast({
          title: 'Approval required',
          description: 'The agent stopped at the policy gate. Open the approval to decide.',
          tone: 'info',
        });
      }
    } else if (state.error) {
      toast({ title: 'The turn did not complete', description: state.error, tone: 'danger' });
    }
  }, [state, toast]);

  return (
    <Card>
      <CardContent className="space-y-4 pt-4">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="text-label font-[510] text-fg">Try it</h2>
          <Badge tone="test" dot>
            test mode
          </Badge>
          {conversationId ? (
            <Link
              href={`/agentic/conversations/${conversationId}`}
              className="ml-auto text-label-sm text-accent-text hover:underline"
            >
              Full conversation &amp; trace →
            </Link>
          ) : null}
        </div>
        <p className="text-label-sm text-fg-subtle">
          Drives the real pipeline. Money tools run against the sandbox provider; anything above the
          policy threshold stops for an approval.
        </p>

        {exchanges.length > 0 ? (
          <ol className="space-y-4">
            {exchanges.map((exchange, i) => (
              <li key={i} className="space-y-2">
                <p className="rounded-lg bg-accent-subtle p-3 text-label-sm text-fg-muted">
                  {exchange.sent}
                </p>
                <TurnView turn={exchange.turn} />
              </li>
            ))}
          </ol>
        ) : null}

        <form ref={formRef} action={formAction} className="space-y-2">
          <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
          <input type="hidden" name="conversationId" value={conversationId ?? ''} />
          <textarea
            name="message"
            rows={2}
            maxLength={4000}
            required
            placeholder='e.g. "Find me a hand grinder and buy one"'
            className="w-full rounded-md bg-surface-inset px-3 py-2 text-label text-fg-muted ring-hairline outline-none focus:ring-2 focus:ring-accent-ring"
          />
          <div className="flex items-center justify-between">
            <span className="text-caption text-fg-subtle">
              {conversationId ? `conversation ${conversationId.slice(0, 8)}…` : 'new conversation'}
            </span>
            <SendButton />
          </div>
        </form>
      </CardContent>
    </Card>
  );
}

function TurnView({ turn }: { turn: AgenticTurn }) {
  return (
    <div className="space-y-2 rounded-lg bg-surface-inset p-3 ring-hairline">
      <div className="flex items-center gap-2">
        <Badge tone="info">agent</Badge>
        <span className="font-mono text-caption text-fg-subtle">{turn.stopReason}</span>
        {turn.approvalId ? (
          <Link
            href={`/agentic/approvals/${turn.approvalId}`}
            className="ml-auto text-label-sm text-accent-text hover:underline"
          >
            Open approval →
          </Link>
        ) : null}
      </div>
      {turn.reply ? (
        <p className="text-label-sm whitespace-pre-wrap text-fg-muted">{turn.reply}</p>
      ) : null}
      {turn.actions.length > 0 ? (
        <ul className="divide-y divide-border-subtle">
          {turn.actions.map((action, i) => (
            <li key={action.actionId ?? i} className="flex flex-wrap items-center gap-2 py-1.5">
              <span className="min-w-[10rem] font-mono text-caption text-fg-muted">
                {action.toolName}
              </span>
              <StatusPill status={action.state} family="action" />
              {action.policyDecision ? (
                <StatusPill status={action.policyDecision} family="policy" />
              ) : null}
              {!action.ok && action.message ? (
                <span className="text-caption text-danger">{action.message}</span>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

function SendButton() {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" variant="primary" disabled={pending}>
      {pending ? 'Running…' : 'Send'}
    </Button>
  );
}
