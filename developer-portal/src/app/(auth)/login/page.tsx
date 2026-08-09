import type { Metadata } from 'next';
import Link from 'next/link';

import { FormAlert } from '@/components/auth/form-alert';
import { readCsrfToken } from '@/lib/security/csrf';
import { DEFAULT_AFTER_LOGIN, safeRedirectPath } from '@/lib/security/redirect';

import { LoginForm } from './login-form';

export const metadata: Metadata = { title: 'Sign in' };

/**
 * The sign-in page (M23.2; entry-flow wiring in M23.2a).
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
 * ── What `?registered=1` may and may not do ───────────────────────────────────────────
 *
 * Signup redirects here with a flag and the address it created. The flag selects **fixed copy
 * written in this file** — nothing from the query string is rendered as a message, because a
 * page that displays arbitrary text from its own URL is a phishing surface with the product's
 * domain on it. The address is different: it goes into an `<input value>`, where it is a string
 * and not markup, and it is shape-checked first so a malformed one is simply dropped rather than
 * pre-filling the form with junk.
 *
 * ── This page must never be cached ────────────────────────────────────────────────────
 *
 * It carries a per-visitor CSRF token. A cached copy would serve one visitor's token to another,
 * which does not leak a session but does break every subsequent sign-in with a token mismatch.
 */
export const dynamic = 'force-dynamic';

/** Same permissive shape the signup action validates with. This only rejects obvious junk. */
const EMAIL_SHAPE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

function first(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = await searchParams;
  const next = safeRedirectPath(first(params.next));

  const justRegistered = first(params.registered) === '1';
  const suggested = first(params.email) ?? '';
  const email = justRegistered && EMAIL_SHAPE.test(suggested) ? suggested.toLowerCase() : '';

  const csrfToken = await readCsrfToken();

  return (
    <div className="rounded-xl bg-surface p-6 ring-hairline">
      <div className="mb-5">
        <h1 className="text-title-2 font-[510] tracking-[-0.165px] text-fg">Sign in</h1>
        <p className="mt-1 text-body text-fg-subtle">
          Use your PaymentFlow account to reach the dashboard.
        </p>
      </div>

      {justRegistered ? (
        <div className="mb-4">
          <FormAlert tone="success">Account created. Sign in to finish setting up.</FormAlert>
        </div>
      ) : null}

      <LoginForm csrfToken={csrfToken} next={next} email={email} />

      <p className="mt-5 text-label text-fg-subtle">
        Don&rsquo;t have an account?{' '}
        <Link
          href={
            next === DEFAULT_AFTER_LOGIN ? '/signup' : `/signup?next=${encodeURIComponent(next)}`
          }
          className="font-[510] text-accent-text underline-offset-4 hover:underline"
        >
          Create one
        </Link>
      </p>
    </div>
  );
}
