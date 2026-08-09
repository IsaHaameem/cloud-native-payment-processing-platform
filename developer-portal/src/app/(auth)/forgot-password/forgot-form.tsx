'use client';

import { MailCheck } from 'lucide-react';
import { useActionState } from 'react';
import { useFormStatus } from 'react-dom';

import { AuthField } from '@/components/auth/auth-field';
import { FormAlert } from '@/components/auth/form-alert';
import { Button } from '@/components/ui/button';
import { CSRF_FIELD } from '@/lib/security/csrf-field';

import { type ForgotPasswordState, forgotPasswordAction } from './actions';

/**
 * The reset-request form, and the state it becomes (M23.2b).
 *
 * ── The confirmation replaces the form ────────────────────────────────────────────────
 *
 * Leaving the form on screen beneath a success message invites the second submission that a user
 * makes when no email has arrived within ten seconds — which spends their allowance and, because
 * each request issues a *new* token, invalidates nothing but adds another live one. Replacing it
 * makes "wait, then check spam, then try again" the path of least resistance, and the resend
 * control below is deliberately quieter than the original button.
 *
 * ── The copy never claims an email was sent ───────────────────────────────────────────
 *
 * "If that address has an account" is doing real work: the server does not know whether it does,
 * and a user who mistyped their address needs the sentence that makes them check it.
 *
 * ── Why the heading lives here rather than in the page ────────────────────────────────
 *
 * Because it has to change with the state, and the state is client-side. With the heading in the
 * Server Component, the confirmation appeared *underneath* "Enter the address you signed up with
 * and we'll send you a link" — an instruction the user had just followed, still telling them to
 * follow it. Caught by looking at the screen rather than by a test, which is the class of defect
 * that only screenshots find.
 */
export function ForgotPasswordForm({ csrfToken }: { csrfToken: string }) {
  const [state, formAction] = useActionState<ForgotPasswordState, FormData>(forgotPasswordAction, {
    sent: false,
    error: undefined,
  });

  if (state.sent) {
    return (
      <div className="flex flex-col items-center gap-4 text-center">
        <span className="flex size-10 items-center justify-center rounded-lg bg-surface-elevated text-fg-subtle ring-hairline">
          <MailCheck aria-hidden className="size-4" />
        </span>
        <div>
          <h1 className="text-title-2 font-[510] tracking-[-0.165px] text-fg">Check your inbox</h1>
          <p className="mt-1.5 text-body text-pretty text-fg-subtle">
            If that address has an account, a link to set a new password is on its way. It expires
            in an hour.
          </p>
        </div>

        {/*
         * A link back to this page, not a re-submit.
         *
         * Re-running the action would return `sent: true` again and re-render this exact panel,
         * telling the user nothing — and submitting a blank address to get past that would be a
         * control whose meaning depends on a quirk. A navigation resets the action state and
         * lands on an empty field, which is also the fix for the commonest cause of a missing
         * email: the address was wrong.
         */}
        <p className="text-label text-fg-faint">
          Wrong address, or nothing arrived?{' '}
          <a
            href="/forgot-password"
            className="font-[510] text-accent-text underline-offset-4 hover:underline"
          >
            Try another
          </a>
        </p>
      </div>
    );
  }

  return (
    <form action={formAction} className="flex flex-col gap-3.5">
      <div className="mb-1.5">
        <h1 className="text-title-2 font-[510] tracking-[-0.165px] text-fg">Reset your password</h1>
        <p className="mt-1 text-body text-fg-subtle">
          Enter the address you signed up with and we&rsquo;ll send you a link.
        </p>
      </div>

      <input type="hidden" name={CSRF_FIELD} value={csrfToken} />

      <AuthField
        label="Email"
        name="email"
        type="email"
        autoComplete="username"
        placeholder="you@company.com"
        maxLength={255}
        required
        autoFocus
      />

      <FormAlert>{state.error}</FormAlert>

      <SubmitButton />
    </form>
  );
}

function SubmitButton() {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" variant="primary" size="lg" className="w-full" disabled={pending}>
      {pending ? 'Sending…' : 'Send reset link'}
    </Button>
  );
}
