/**
 * The shape and initial value of the sandbox-override Server Actions' result.
 *
 * Separate from `actions.ts` because that module is `'use server'`, where every export must be
 * an async function — importing a non-function value from one into a client component fails at
 * request time with "A 'use server' file can only export async functions, found object."
 */
export interface SandboxActionState {
  readonly ok: boolean;
  readonly error: string | undefined;
  readonly requestId: string | undefined;
}

export const SANDBOX_IDLE: SandboxActionState = {
  ok: false,
  error: undefined,
  requestId: undefined,
};
