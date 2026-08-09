import { cookies } from 'next/headers';

import { AppHeader } from '@/components/layout/app-header';
import { AppSidebar, SIDEBAR_COLLAPSED_COOKIE } from '@/components/layout/app-sidebar';
import { ModeBanner } from '@/components/layout/mode-banner';
import { PageTransition } from '@/components/layout/page-transition';
import { currentMerchant } from '@/lib/platform/current-merchant';
import { readCsrfToken } from '@/lib/security/csrf';
import { requireSession } from '@/lib/session/require';
import { toPublicSession } from '@/lib/session/session';

/**
 * The authenticated shell (M23.1 shape, M23.2 authorization).
 *
 * ── What this layout is for ───────────────────────────────────────────────────────────
 *
 * A route group rather than a path segment, so `/payments` stays `/payments` and does not
 * become `/dashboard/payments` merely because it shares chrome. Every page under `(app)`
 * inherits the sidebar, the header, the mode banner — and the authorization precondition — by
 * being here, in one place instead of once per page.
 *
 * ── `requireSession` here is the security boundary; the middleware is not ─────────────
 *
 * The middleware redirects an unauthenticated request before this renders, which is what
 * prevents a flash of protected content. But it is matched by a path pattern, and a pattern is
 * the wrong thing to rest authorization on. This call is the check that cannot be missed by a
 * routing change, and — because it is also the only source of a credential — a page under this
 * layout that forgets to ask for a session has nothing to fetch merchant data *with*.
 *
 * ── Why the transition wraps `children` and not the shell ─────────────────────────────
 *
 * The rail and the header are *not* inside `PageTransition`. A dashboard's shell is furniture:
 * it should stay perfectly still while the panel it frames changes, which is what makes a
 * navigation feel instant rather than like a page load. Fading the sidebar on every route
 * change is the single most common way an otherwise-good transition makes an app feel slower
 * than it is.
 *
 * ── Why the business name is looked up rather than read from the session ──────────────
 *
 * The shell names the business the user is acting as, which the session cookie deliberately does
 * not carry — see `lib/platform/current-merchant.ts` for why display data stays out of a value
 * sent on every request. The lookup is memoised for the render, so the page below reuses this
 * one call rather than making a second.
 *
 * A failed lookup yields no name rather than a placeholder. The shell is chrome: it either
 * states a fact or says nothing, and "Loading…" or "Unknown business" frozen in a header is the
 * kind of detail that makes a whole product feel unreliable.
 *
 * ── Why the sidebar's state is read here ──────────────────────────────────────────────
 *
 * The collapsed preference is a cookie so this server component can render the correct width in
 * the first HTML. Reading it from `localStorage` would render the rail wide and snap it narrow
 * after hydration — a layout shift on the element that frames every page.
 */
export default async function AppLayout({ children }: { children: React.ReactNode }) {
  const session = await requireSession();

  const cookieStore = await cookies();
  const collapsed = cookieStore.get(SIDEBAR_COLLAPSED_COOKIE)?.value === 'true';
  const csrfToken = await readCsrfToken();

  // Only ever the public projection crosses into the tree below. `toPublicSession` cannot carry
  // a token, which is what makes that a structural guarantee rather than a habit.
  const publicSession = toPublicSession(session);

  const lookup =
    session.merchantId === undefined ? undefined : await currentMerchant(session.accessToken);
  const businessName =
    lookup?.status === 'found' && lookup.merchant.businessName.length > 0
      ? lookup.merchant.businessName
      : undefined;

  return (
    <div className="flex min-h-dvh bg-canvas">
      <AppSidebar defaultCollapsed={collapsed} />

      <div className="flex min-w-0 flex-1 flex-col">
        <ModeBanner mode={publicSession.mode} />
        <AppHeader session={publicSession} businessName={businessName} csrfToken={csrfToken} />

        <main id="main" className="flex-1 px-5 py-8 sm:px-8 lg:px-10">
          <div className="mx-auto w-full max-w-[1200px]">
            <PageTransition>{children}</PageTransition>
          </div>
        </main>
      </div>
    </div>
  );
}
