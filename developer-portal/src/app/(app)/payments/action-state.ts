/**
 * The shape and initial value of the payment-FSM Server Actions' result.
 *
 * ── Why this is a separate module from `actions.ts` ───────────────────────────────────
 *
 * `actions.ts` carries `'use server'`, and **every export of a `'use server'` module must be an
 * async function** — a client component that imports a non-function value from one triggers
 * "A 'use server' file can only export async functions, found object." at request time. The
 * `useActionState` hook on the client needs an initial value (`IDLE`) and the state type, so
 * both live here, in a plain module both sides can import.
 */
export interface PaymentActionState {
  readonly ok: boolean;
  /** A message to show on failure. */
  readonly error: string | undefined;
  /** The platform's own code and request id, for a support handle. */
  readonly code: string | undefined;
  readonly requestId: string | undefined;
}

export const IDLE: PaymentActionState = {
  ok: false,
  error: undefined,
  code: undefined,
  requestId: undefined,
};
