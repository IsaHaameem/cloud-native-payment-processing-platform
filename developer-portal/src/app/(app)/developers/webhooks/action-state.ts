/**
 * The shape and initial value of the webhook Server Actions' result.
 *
 * Separate from `actions.ts` because that module is `'use server'`, where every export must be
 * an async function — importing a non-function value from one into a client component fails at
 * request time with "A 'use server' file can only export async functions, found object."
 */
export interface WebhookActionState {
  readonly ok: boolean;
  readonly error: string | undefined;
  readonly requestId: string | undefined;
  /** The full signing secret, present only in the create/rotate response that produced it. */
  readonly secret: string | undefined;
  /** A completed action with nothing to reveal — the client turns this into a refetch. */
  readonly done: 'created' | 'rotated' | 'updated' | 'deleted' | 'replayed' | undefined;
}

export const WEBHOOK_IDLE: WebhookActionState = {
  ok: false,
  error: undefined,
  requestId: undefined,
  secret: undefined,
  done: undefined,
};
