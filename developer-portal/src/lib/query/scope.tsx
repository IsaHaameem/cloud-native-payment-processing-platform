'use client';

import * as React from 'react';

import { type QueryScope } from '@/lib/query/keys';
import { type PublicSession } from '@/lib/session/session';

/**
 * Which merchant and which mode the client tree is reading (M23.3).
 *
 * ── This is not the global store D191 rejected ────────────────────────────────────────
 *
 * It holds no state. The value is decided on the server, from the sealed session, and passed
 * down once; nothing in the tree can set it. Changing mode is still a guarded `POST` that
 * reloads the document (`mode-switch.tsx`), so this context is re-created from a new session
 * rather than mutated — which is precisely the property that makes a stale read impossible.
 *
 * It exists because every query key needs the scope and threading two strings through every
 * component that fetches would guarantee that one of them eventually forgets. Reading the scope
 * from context means a hook can build a correctly-scoped key without its caller knowing the
 * rule.
 *
 * ── Why it throws when absent ─────────────────────────────────────────────────────────
 *
 * A missing provider is a component fetching outside the authenticated shell, where there is no
 * merchant to scope to. Defaulting would produce a key that silently collides across merchants —
 * the exact failure `keys.ts` is built to prevent — so the failure is loud and immediate instead.
 */
const ScopeContext = React.createContext<QueryScope | null>(null);

export function QueryScopeProvider({
  session,
  children,
}: {
  /** The public projection — by construction it cannot carry a token. */
  session: PublicSession;
  children: React.ReactNode;
}) {
  const scope = React.useMemo<QueryScope | null>(
    () =>
      session.merchantId === undefined
        ? null
        : { merchantId: session.merchantId, mode: session.mode },
    [session.merchantId, session.mode],
  );

  return <ScopeContext.Provider value={scope}>{children}</ScopeContext.Provider>;
}

/**
 * @returns the scope every query key starts with.
 * @throws when called outside the authenticated shell, or by a user with no merchant.
 */
export function useQueryScope(): QueryScope {
  const scope = React.useContext(ScopeContext);
  if (!scope) {
    throw new Error(
      'useQueryScope was called outside a QueryScopeProvider with a merchant. A component that ' +
        'reads platform data must render inside the authenticated shell.',
    );
  }
  return scope;
}

/**
 * @returns the scope, or `null` for a signed-in user who has not onboarded.
 *
 * For the handful of components that render in both states — the command palette is one, since
 * it is mounted by the shell before a merchant necessarily exists — and must degrade rather than
 * throw.
 */
export function useOptionalQueryScope(): QueryScope | null {
  return React.useContext(ScopeContext);
}
