'use client';

import Link from 'next/link';
import { useActionState } from 'react';
import { useFormStatus } from 'react-dom';

import { AuthField } from '@/components/auth/auth-field';
import { FormAlert } from '@/components/auth/form-alert';
import { Button } from '@/components/ui/button';
import { CSRF_FIELD } from '@/lib/security/csrf-field';

import { type LoginState, loginAction } from './actions';

/**
 * The sign-in form (M23.2; fields extracted to `components/auth` in M23.2a).
 *
 * ── Progressive enhancement is the point, not a nicety ────────────────────────────────
 *
 * A plain `<form action={serverAction}>`. With JavaScript it posts without navigating and shows a
 * pending state; without it, the browser submits and follows the redirect. The entry point to an
 * application is the one screen where a failed bundle must not mean a locked door.
 *
 * ── Why the token is a hidden field and not a header ──────────────────────────────────
 *
 * A header would need JavaScript to attach, which would undo the paragraph above. A hidden field
 * travels with a native form submission, so the CSRF defence holds on both paths.
 *
 * ── The error is never attached to a field ────────────────────────────────────────────
 *
 * Unlike signup, which can say *which* input was wrong, sign-in deliberately cannot: marking the
 * email field would say the address is unknown, and marking the password field would say it is
 * known. One message, below both, is the shape that keeps the page non-enumerable.
 */
export function LoginForm({
  csrfToken,
  next,
  email,
}: {
  csrfToken: string;
  next: string;
  /** Pre-filled after registration, so the address is not typed twice. */
  email: string;
}) {
  const [state, formAction] = useActionState<LoginState, FormData>(loginAction, {
    error: undefined,
  });

  return (
    <form action={formAction} className="flex flex-col gap-3.5">
      <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
      <input type="hidden" name="next" value={next} />

      <AuthField
        label="Email"
        name="email"
        type="email"
        autoComplete="username"
        placeholder="you@company.com"
        defaultValue={email}
        required
        // Focus follows the first empty field: a returning user starts at the address, someone
        // arriving from signup starts at the password, which is the only thing left to type.
        autoFocus={email.length === 0}
      />
      {/*
       * The recovery link sits beside the password label rather than below the button.
       *
       * That is where someone looks at the moment they realise they cannot remember it — while
       * staring at the field — and it keeps the space under the primary action free for the one
       * thing that belongs there. `AuthField`'s `hint` slot already exists for exactly this kind
       * of right-aligned label companion, so it costs no new layout.
       */}
      <AuthField
        label="Password"
        name="password"
        hintNode={
          <Link
            href="/forgot-password"
            className="text-label-sm text-fg-subtle underline-offset-4 transition-colors duration-(--duration-fast) hover:text-fg hover:underline"
          >
            Forgot password?
          </Link>
        }
        type="password"
        autoComplete="current-password"
        placeholder="••••••••••••"
        required
        reveal
        autoFocus={email.length > 0}
      />

      <FormAlert>{state.error}</FormAlert>

      <SubmitButton />
    </form>
  );
}

function SubmitButton() {
  // `useFormStatus` must be read from a component *inside* the form, which is the only reason
  // this is a separate component rather than a line in the one above.
  const { pending } = useFormStatus();
  return (
    <Button type="submit" variant="primary" size="lg" className="w-full" disabled={pending}>
      {pending ? 'Signing in…' : 'Sign in'}
    </Button>
  );
}
