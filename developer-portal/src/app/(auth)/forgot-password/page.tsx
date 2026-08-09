import type { Metadata } from 'next';
import Link from 'next/link';

import { readCsrfToken } from '@/lib/security/csrf';

import { ForgotPasswordForm } from './forgot-form';

export const metadata: Metadata = { title: 'Reset your password' };

/**
 * Where a user asks for a reset link (M23.2b).
 *
 * A Server Component, so the CSRF token is minted server-side and rendered into the form — the
 * same arrangement every entry page uses, and for the same reason: `readCsrfToken` is read-only
 * because a Server Component cannot set a cookie, and the middleware guarantees the cookie exists
 * before any render reads it.
 *
 * Reachable while signed in, deliberately. It is not in the middleware's `ENTRY_PATHS`, so a
 * signed-in user who has forgotten their password — the ordinary case for someone who signed in
 * on this machine months ago and now wants to change it — is not bounced to the dashboard.
 *
 * `force-dynamic` because the page carries a per-visitor CSRF token, and a cached copy would
 * serve one visitor's token to another.
 */
export const dynamic = 'force-dynamic';

export default async function ForgotPasswordPage() {
  const csrfToken = await readCsrfToken();

  return (
    <div className="rounded-xl bg-surface p-6 ring-hairline">
      {/*
       * The heading is inside the form component, not here. It has to change when the
       * confirmation replaces the fields — see `forgot-form.tsx` for what it looked like when it
       * did not.
       */}
      <ForgotPasswordForm csrfToken={csrfToken} />

      <p className="mt-5 text-label text-fg-subtle">
        Remembered it?{' '}
        <Link
          href="/login"
          className="font-[510] text-accent-text underline-offset-4 hover:underline"
        >
          Back to sign in
        </Link>
      </p>
    </div>
  );
}
