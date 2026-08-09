import { SiteFooter } from '@/components/marketing/site-footer';
import { SiteHeader } from '@/components/marketing/site-header';
import { readSession } from '@/lib/session/require';

/**
 * The public shell (M23.2a).
 *
 * ── Why the landing page needs to know about the session at all ───────────────────────
 *
 * A signed-in visitor arriving at `/` — from a bookmark, from a link, from the wordmark in the
 * portal's own auth pages — should be offered their dashboard, not a "Get started" button for an
 * account they already have. That is the one thing the public chrome asks the server, and
 * `readSession` is the guard that answers it without redirecting: `/` renders for everyone.
 *
 * Only the boolean crosses into the tree. The header takes `signedIn`, not a session, so the
 * public surface cannot render an email — let alone a token — by anyone's accident.
 *
 * ── It has no `<main id="main">` ──────────────────────────────────────────────────────
 *
 * The page does, wrapped around the hero. The skip link in the root layout targets that id, and
 * a landmark declared here would put the navbar inside the region "skip to content" is supposed
 * to skip past.
 */
export default async function MarketingLayout({ children }: { children: React.ReactNode }) {
  const session = await readSession();

  return (
    <div className="flex min-h-dvh flex-col bg-canvas">
      <SiteHeader signedIn={session !== null} />
      <div className="flex-1">{children}</div>
      <SiteFooter signedIn={session !== null} />
    </div>
  );
}
