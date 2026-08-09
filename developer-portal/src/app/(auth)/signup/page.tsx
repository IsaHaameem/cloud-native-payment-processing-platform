import type { Metadata } from 'next';
import Link from 'next/link';

import { readCsrfToken } from '@/lib/security/csrf';
import { DEFAULT_AFTER_LOGIN, safeRedirectPath } from '@/lib/security/redirect';

import { SignupForm } from './signup-form';

export const metadata: Metadata = { title: 'Create account' };

/**
 * Account creation (M23.2a).
 *
 * A Server Component, so the CSRF token is minted server-side and rendered into the form — the
 * same arrangement as `/login`, and for the same reason: `readCsrfToken` is read-only because a
 * Server Component cannot set a cookie, and the middleware guarantees the cookie exists before
 * any render reads it.
 *
 * It does not check for an existing session. The middleware already redirects a signed-in
 * visitor away from both entry paths, and duplicating that here would mean two places deciding
 * where an authenticated user belongs.
 *
 * `force-dynamic` for the same reason `/login` needs it: the page carries a per-visitor CSRF
 * token, and a cached copy would serve one visitor's token to another — which leaks no session
 * but breaks every subsequent submission with a token mismatch.
 */
export const dynamic = 'force-dynamic';

export default async function SignupPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = await searchParams;
  const requested = params.next;
  // Validated on the way *in*, not only on submit: the value round-trips through a hidden field,
  // so an unvalidated one would sit in the DOM of a page whose URL the attacker chose.
  const next = safeRedirectPath(Array.isArray(requested) ? requested[0] : requested);

  const csrfToken = await readCsrfToken();

  return (
    <div className="rounded-xl bg-surface p-6 ring-hairline">
      <div className="mb-5">
        <h1 className="text-title-2 font-[510] tracking-[-0.165px] text-fg">Create your account</h1>
        <p className="mt-1 text-body text-fg-subtle">
          Start in test mode. No card details, no charges until you say so.
        </p>
      </div>

      <SignupForm csrfToken={csrfToken} next={next} />

      <p className="mt-5 text-label text-fg-subtle">
        Already have an account?{' '}
        {/*
         * A `next` the visitor arrived with is carried across, so switching between the two
         * entry pages does not lose the deep link that sent them here.
         */}
        <Link
          href={next === DEFAULT_AFTER_LOGIN ? '/login' : `/login?next=${encodeURIComponent(next)}`}
          className="font-[510] text-accent-text underline-offset-4 hover:underline"
        >
          Sign in
        </Link>
      </p>
    </div>
  );
}
