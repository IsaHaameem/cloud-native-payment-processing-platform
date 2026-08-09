import Link from 'next/link';

import { Wordmark } from '@/components/layout/logo';
import { CSRF_FIELD } from '@/lib/security/csrf-field';
import { readCsrfToken } from '@/lib/security/csrf';
import { requireSession } from '@/lib/session/require';

/**
 * The shell for a signed-in user who has nowhere to act yet (M23.2a).
 *
 * ── Why this is not the `(app)` group ─────────────────────────────────────────────────
 *
 * Onboarding is authenticated, so putting it under the application shell is the obvious move and
 * it is wrong in a specific way: that shell is a sidebar of destinations this user cannot reach,
 * a mode switch between two data planes they do not have, and a command menu over a product they
 * have not entered. It frames the one task they must complete with ten they cannot, which reads
 * as a broken dashboard rather than as a setup step.
 *
 * So: its own group, its own minimal chrome, and `requireSession` here rather than
 * `requireMerchant` — this is the page for people the merchant guard turned away, and a group
 * whose layout demanded a merchant would be a redirect loop with itself.
 *
 * Route groups do not appear in the URL, so the path is still `/onboarding` and every existing
 * redirect to it is untouched.
 *
 * ── Sign out is present, and that is deliberate ───────────────────────────────────────
 *
 * Without it this page is a trap: a user who signed into the wrong account can neither continue
 * (the merchant would be created under it) nor leave. It is the same guarded form post the
 * account menu uses — CSRF token, `POST`, no `GET` handler at the other end.
 */
export default async function SetupLayout({ children }: { children: React.ReactNode }) {
  await requireSession();
  const csrfToken = await readCsrfToken();

  return (
    <div className="relative flex min-h-dvh flex-col overflow-hidden bg-canvas">
      <div aria-hidden className="bg-grid bg-grid-fade absolute inset-0 -z-10 opacity-60" />

      <header className="flex h-14 shrink-0 items-center justify-between px-5 sm:px-8">
        <Link href="/" aria-label="PaymentFlow home" className="rounded-md">
          <Wordmark />
        </Link>

        <form action="/logout" method="post">
          <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
          <button
            type="submit"
            className="rounded-md px-2 py-1 text-label text-fg-subtle transition-colors duration-(--duration-fast) hover:bg-surface-hover hover:text-fg"
          >
            Sign out
          </button>
        </form>
      </header>

      <main id="main" className="flex flex-1 items-center justify-center px-4 py-10">
        {children}
      </main>
    </div>
  );
}
