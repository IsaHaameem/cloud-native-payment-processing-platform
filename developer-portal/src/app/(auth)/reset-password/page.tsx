import { AlertCircle } from 'lucide-react';
import type { Metadata } from 'next';
import Link from 'next/link';

import { Button } from '@/components/ui/button';
import { readCsrfToken } from '@/lib/security/csrf';

import { ResetPasswordForm } from './reset-form';

export const metadata: Metadata = {
  title: 'Set a new password',
  // Belt and braces over the root layout's `noindex`: this URL carries a credential in its query
  // string, and a crawler that followed a leaked one must not put it in an index.
  robots: { index: false, follow: false },
};

/**
 * Where a reset link lands (M23.2b).
 *
 * ── The route is not a choice ─────────────────────────────────────────────────────────
 *
 * `IdentityEventPublisher.publish` builds the emailed link as `{baseUrl}/reset-password?token=…`,
 * and `application.yaml` sets `base-url: http://localhost:3000` with a comment saying it points
 * at a frontend that does not exist yet. So the path and the parameter name were fixed by the
 * backend in M15; this page is the other end of a link that has been being sent for eight
 * milestones. Nothing here was invented, and nothing here may be renamed without changing
 * identity-service.
 *
 * ── A missing token gets its own state, not a broken form ─────────────────────────────
 *
 * Arriving without one means the link was truncated by a mail client, or the URL was typed by
 * hand. Rendering the form anyway would let someone fill in a password and only then be told the
 * link was never valid — the most annoying possible ordering. The page checks first.
 *
 * It does **not** check whether the token is *good*, and that is deliberate: verifying it would
 * mean spending it, since `confirmReset` is the only operation identity-service exposes and it is
 * single-use. An expired or already-used token is therefore reported on submit, which is the
 * earliest point it can honestly be known.
 *
 * ── The token is read but never persisted ─────────────────────────────────────────────
 *
 * Straight from the query string into a hidden field. Not into a cookie, not into storage, not
 * into a log. `Referrer-Policy: strict-origin-when-cross-origin` (next.config.ts) keeps it out of
 * the `Referer` on any outbound navigation.
 */
export const dynamic = 'force-dynamic';

export default async function ResetPasswordPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = await searchParams;
  const raw = params.token;
  const token = (Array.isArray(raw) ? raw[0] : raw) ?? '';

  if (token.length === 0) {
    return (
      <div className="rounded-xl bg-surface p-6 ring-hairline">
        <div className="flex flex-col items-center gap-4 py-2 text-center">
          <span className="flex size-10 items-center justify-center rounded-lg bg-danger-surface text-danger">
            <AlertCircle aria-hidden className="size-4" />
          </span>
          <div>
            <p className="text-body font-[510] text-fg">This link is incomplete</p>
            <p className="mt-1.5 text-body text-pretty text-fg-subtle">
              It may have been cut short by your email client. Request a new one and open it in a
              single click.
            </p>
          </div>
          <Button variant="primary" size="lg" className="w-full" asChild>
            <Link href="/forgot-password">Request a new link</Link>
          </Button>
        </div>
      </div>
    );
  }

  const csrfToken = await readCsrfToken();

  return (
    <div className="rounded-xl bg-surface p-6 ring-hairline">
      <div className="mb-5">
        <h1 className="text-title-2 font-[510] tracking-[-0.165px] text-fg">Set a new password</h1>
        <p className="mt-1 text-body text-fg-subtle">
          Choose a new password for your account. This signs you out everywhere else.
        </p>
      </div>

      <ResetPasswordForm csrfToken={csrfToken} token={token} />

      <p className="mt-5 text-label text-fg-subtle">
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
