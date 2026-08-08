import type { Metadata } from 'next';

import { readCsrfToken } from '@/lib/security/csrf';
import { safeRedirectPath } from '@/lib/security/redirect';

import { LoginForm } from './login-form';

export const metadata: Metadata = { title: 'Sign in' };

/**
 * The sign-in page (M23.2).
 *
 * A Server Component, so the CSRF token is minted server-side and rendered into the form. It does
 * not check for an existing session: the middleware already redirects a signed-in visitor away
 * from `/login`, and duplicating that here would mean two places deciding where an authenticated
 * user belongs.
 *
 * ── `next` is validated here, not only on submit ──────────────────────────────────────
 *
 * The value round-trips through a hidden field, so an unvalidated one would be sanitised on
 * submit but still sit in the DOM of a page the attacker chose the URL for. Validating on the way
 * in means the dangerous value never reaches the document at all.
 *
 * ── This page must never be cached ────────────────────────────────────────────────────
 *
 * It carries a per-visitor CSRF token. A cached copy would serve one visitor's token to another,
 * which does not leak a session but does break every subsequent sign-in with a token mismatch.
 */
export const dynamic = 'force-dynamic';

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = await searchParams;
  const requested = params.next;
  const next = safeRedirectPath(Array.isArray(requested) ? requested[0] : requested);

  const csrfToken = await readCsrfToken();

  return (
    <div className="rounded-xl bg-surface p-6 ring-hairline">
      <div className="mb-5">
        <h1 className="text-title-1 font-[510] tracking-[-0.37px] text-fg">Sign in</h1>
        <p className="mt-1 text-body text-fg-subtle">
          Use your PaymentFlow account to reach the dashboard.
        </p>
      </div>

      <LoginForm csrfToken={csrfToken} next={next} />
    </div>
  );
}
