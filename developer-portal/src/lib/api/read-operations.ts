import { OPERATIONS, type OperationDescriptor } from '@/generated/operations';

/**
 * The operations a browser may ask the portal to perform on its behalf (M23.3).
 *
 * ── Reads only, and the list is derived rather than written ───────────────────────────
 *
 * §6.6 asks for "a client query library for interactive lists with cursor pagination", which
 * needs an endpoint a client component can call — the browser cannot call the gateway itself
 * (D187) and must never hold a token to try. This module decides what that endpoint will accept.
 *
 * The answer is: every **GET** in the generated contract, and nothing else. Three consequences,
 * all deliberate:
 *
 * 1. **No mutation is reachable from the browser through this path.** Capture, refund and void
 *    stay in Server Actions, where M23.2's CSRF token and origin assertion guard them and where
 *    D190's confirmation dialogs live. A read proxy that could be talked into a `POST` would
 *    undo that in one line, so it cannot be: the filter is on the descriptor's own `method`.
 * 2. **The set cannot drift from the platform.** It is computed from `OPERATIONS`, which
 *    `:sdks:shared` regenerates from `docs/openapi.yaml` and `verifySdkSources` fails the build
 *    over when stale. A hand-maintained allowlist would keep answering the old question after
 *    the contract moved — the failure D166 exists to prevent.
 * 3. **A new read endpoint works the day it is published**, with no portal change. That is the
 *    property that makes this the *data layer* rather than a list of special cases.
 *
 * What it emphatically does not grant is access to another merchant's data. Scoping is the
 * gateway's, from the signed internal context M23.0 derives from the session — the browser
 * chooses an operation, never a merchant, and never a mode.
 */

/** A read the portal will perform for a client component. */
export interface ReadOperation {
  readonly descriptor: OperationDescriptor;
  /** Names of the `{placeholders}` in the path template, in the order they appear. */
  readonly pathParameters: readonly string[];
}

const PLACEHOLDER = /\{([^}]+)\}/g;

function pathParametersOf(path: string): readonly string[] {
  return [...path.matchAll(PLACEHOLDER)].map(([, name]) => name as string);
}

const READ_OPERATIONS: ReadonlyMap<string, ReadOperation> = new Map(
  Object.values(OPERATIONS)
    .filter((descriptor) => (descriptor as OperationDescriptor).method === 'GET')
    .map((descriptor) => {
      const typed = descriptor as OperationDescriptor;
      return [typed.id, { descriptor: typed, pathParameters: pathParametersOf(typed.path) }];
    }),
);

/**
 * @returns the operation, or `undefined` when the name is unknown **or is not a read**.
 *
 * One function for both questions on purpose. A caller that could distinguish "no such
 * operation" from "that one is a mutation" would be told, by the portal, which mutations exist —
 * a small disclosure, and one with no use to anybody legitimate.
 */
export function readOperation(operationId: string | undefined): ReadOperation | undefined {
  if (operationId === undefined) return undefined;
  return READ_OPERATIONS.get(operationId);
}

/** Exposed for the test that asserts no mutation ever appears in this set. */
export function readOperationIds(): readonly string[] {
  return [...READ_OPERATIONS.keys()];
}
