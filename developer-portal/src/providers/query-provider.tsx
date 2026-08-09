'use client';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as React from 'react';

import { PlatformRequestError } from '@/lib/query/platform';

/**
 * The client query cache (M23.3, D191).
 *
 * ── Why a query library in an application that renders on the server ─────────────────
 *
 * §6.6 splits the job: "Server components for initial load; a client query library for
 * interactive lists with cursor pagination and cache invalidation on mutation." The first half is
 * already true — every page fetches through `callAs` while rendering. This is the second half,
 * and the three things it buys are the three a Server Component genuinely cannot do: load the
 * next cursor page without re-rendering the route, refetch a list after a mutation without a full
 * navigation, and poll an active screen.
 *
 * D191 chose this over a global store, and the reasoning holds here: every candidate for shared
 * client state turned out to be server state, URL state or cookie state. This library owns
 * exactly the first, and the portal still has no store.
 *
 * ── The client is created per mount, not at module scope ─────────────────────────────
 *
 * A `QueryClient` in a module-level constant is shared by every request the server handles, which
 * on a server-rendered application means one merchant's cache answering another merchant's
 * render. `useState` gives each browser tab its own and each SSR pass a fresh one. This is the
 * single most common way a Next.js data layer leaks data between users, and it is one line to
 * avoid.
 *
 * ── Defaults chosen for a dashboard about money ──────────────────────────────────────
 *
 * `retry` refuses to repeat anything the platform refused on purpose. A 401 is not transient — it
 * means the session is over, and retrying it three times delays the redirect the user needs by a
 * second and a half. A 4xx is not transient either. Only genuine 5xx and transport failures are
 * worth a second attempt.
 *
 * `refetchOnWindowFocus` stays **on**: a merchant who alt-tabs back to a payments list expects it
 * to be current, and this is the one place staleness has a real cost.
 *
 * `staleTime` is 30 seconds rather than zero so that moving between two screens that read the
 * same list does not re-request it on every navigation, and short enough that nothing on screen
 * is meaningfully old. Mode switches do not rely on it — they change the key (`lib/query/keys.ts`)
 * and, because the switch is a full form post, discard this cache entirely.
 */
export function QueryProvider({ children }: { children: React.ReactNode }) {
  const [client] = React.useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 30_000,
            refetchOnWindowFocus: true,
            retry: (failureCount, error) => {
              if (error instanceof PlatformRequestError) {
                // 401 and every other 4xx are answers, not accidents.
                if (error.status < 500) return false;
              }
              return failureCount < 1;
            },
          },
          mutations: {
            // Mutations in this portal are captures, refunds and voids. D190 is explicit that
            // they are never optimistic; they are also never automatically retried, because a
            // retry the user did not ask for is a second attempt at moving money.
            retry: false,
          },
        },
      }),
  );

  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
