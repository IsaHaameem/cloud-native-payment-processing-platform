import { type AgenticTurn } from '@/lib/agentic/types';

/**
 * The shape and initial value of the approval-decision Server Actions' result.
 *
 * Separate from `actions.ts` because that module is `'use server'`, where every export must be
 * an async function — importing a non-function value from one into a client component fails at
 * request time with "A 'use server' file can only export async functions, found object."
 */
export interface ApprovalActionState {
  readonly ok: boolean;
  readonly error: string | undefined;
  readonly requestId: string | undefined;
  /** Present after a successful approve — the turn the redeemed approval produced. */
  readonly turn: AgenticTurn | undefined;
  /** A completed decision with nothing more to show; the client refetches the queue. */
  readonly done: 'approved' | 'denied' | undefined;
}

export const APPROVAL_IDLE: ApprovalActionState = {
  ok: false,
  error: undefined,
  requestId: undefined,
  turn: undefined,
  done: undefined,
};
