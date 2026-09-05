import { type AgenticTurn } from '@/lib/agentic/types';

/**
 * The shape and initial value of the agent-console Server Action's result.
 *
 * Kept out of `actions.ts` because that module is `'use server'`, where every export must be an
 * async function — a client component importing a non-function value from one fails at request
 * time with "A 'use server' file can only export async functions, found object." The console's
 * `useActionState` needs the initial value and the type, so both live here.
 */
export interface AgentConsoleState {
  readonly ok: boolean;
  readonly error: string | undefined;
  readonly requestId: string | undefined;
  /** The conversation this console is bound to — echoed back so the next turn continues it. */
  readonly conversationId: string | undefined;
  /** The message that was just sent, so the client can show it in the transcript. */
  readonly sentMessage: string | undefined;
  readonly turn: AgenticTurn | undefined;
}

export const AGENT_CONSOLE_IDLE: AgentConsoleState = {
  ok: false,
  error: undefined,
  requestId: undefined,
  conversationId: undefined,
  sentMessage: undefined,
  turn: undefined,
};
