import { BookOpen } from 'lucide-react';

import { AccountMenu } from '@/components/layout/account-menu';
import { Breadcrumbs } from '@/components/layout/breadcrumbs';
import { CommandMenu } from '@/components/layout/command-menu';
import { MobileNav } from '@/components/layout/mobile-nav';
import { ModeSwitch } from '@/components/layout/mode-switch';
import { ThemeToggle } from '@/components/layout/theme-toggle';
import { Button } from '@/components/ui/button';
import { type PublicSession } from '@/lib/session/session';

/**
 * The sticky top bar (M23.1 design, M23.2 identity).
 *
 * 48px rather than 56px — the system is explicitly "high-density", and the reference's own
 * product chrome is shallow. A server component, deliberately: it holds four client islands
 * (the drawer trigger, the command menu, the mode switch and the account menu) and nothing else
 * needs to ship as JavaScript. The pattern is the one the rest of the portal follows —
 * interactivity is a leaf, not the trunk.
 *
 * `glass` is used here and nowhere else in the chrome. The reference's interaction evidence
 * includes a 20px backdrop blur, and a bar that content scrolls under is where it earns its keep.
 *
 * The search control sits in the centre rather than the right cluster, which is where this class
 * of product puts it — it is the primary way a keyboard-first user moves around, not an
 * accessory to the account menu.
 *
 * It receives a `PublicSession`, never a `Session`. The whole header subtree is therefore
 * incapable of rendering a token into the document, by type rather than by review.
 */
export function AppHeader({
  session,
  businessName,
  csrfToken,
}: {
  session: PublicSession;
  /** Absent when the lookup failed or the user has no merchant. Never a placeholder. */
  businessName?: string | undefined;
  csrfToken: string;
}) {
  return (
    <header className="glass sticky top-0 z-30 flex h-12 shrink-0 items-center gap-2 border-b border-border-subtle px-4">
      <MobileNav />

      {/*
       * Breadcrumbs land here in M23.3, once there is a route tree deep enough to need them.
       * The mode control holds the slot meanwhile, and because "which data am I looking at" is
       * the first question the header should answer — and now the first one it can also change.
       */}
      <div className="flex min-w-0 items-center gap-2">
        {/*
         * Which business, then which data plane — the two facts that qualify everything else on
         * the screen. Hidden below `sm`, where the same answer is one tap away in the account
         * menu and the mode control is the one that must not be.
         */}
        {businessName ? (
          <>
            <span className="hidden max-w-[22ch] truncate text-label font-[510] text-fg sm:block">
              {businessName}
            </span>
            <span aria-hidden className="hidden h-3.5 w-px bg-border sm:block" />
          </>
        ) : null}
        <ModeSwitch mode={session.mode} csrfToken={csrfToken} />

        {/*
         * The trail sits *after* the mode switch, which is the opposite of the usual order and is
         * deliberate: mode qualifies everything on the screen, so it must be the first thing read
         * and the last thing lost when the header runs out of room. Breadcrumbs hide themselves
         * below `sm` and on one-level routes (M23.3).
         */}
        <Breadcrumbs className="ml-1" />
      </div>

      <div className="flex flex-1 justify-center">
        <CommandMenu />
      </div>

      <div className="flex shrink-0 items-center gap-0.5">
        <Button variant="ghost" size="icon" asChild>
          <a
            href="https://github.com/"
            target="_blank"
            rel="noreferrer noopener"
            aria-label="Documentation"
          >
            <BookOpen />
          </a>
        </Button>
        <ThemeToggle />
        <AccountMenu email={session.email} businessName={businessName} csrfToken={csrfToken} />
      </div>
    </header>
  );
}
