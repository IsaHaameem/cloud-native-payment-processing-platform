'use client';

import { AlertCircle } from 'lucide-react';
import { useActionState } from 'react';
import { useFormStatus } from 'react-dom';

import { Button } from '@/components/ui/button';
import { CSRF_FIELD } from '@/lib/security/csrf-field';

import { type LoginState, loginAction } from './actions';

/**
 * The sign-in form (M23.2).
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
 * ── The error region is live ──────────────────────────────────────────────────────────
 *
 * `role="alert"` on a container that is always in the tree, rather than one that appears with
 * the message. A region inserted at the moment it gains content is announced inconsistently
 * across screen readers; one that is present and changes is announced reliably.
 */
export function LoginForm({ csrfToken, next }: { csrfToken: string; next: string }) {
  const [state, formAction] = useActionState<LoginState, FormData>(loginAction, {
    error: undefined,
  });

  return (
    <form action={formAction} className="flex flex-col gap-3.5">
      <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
      <input type="hidden" name="next" value={next} />

      <Field
        label="Email"
        name="email"
        type="email"
        autoComplete="username"
        placeholder="you@company.com"
        required
        autoFocus
      />
      <Field
        label="Password"
        name="password"
        type="password"
        autoComplete="current-password"
        placeholder="••••••••••••"
        required
      />

      <div role="alert" aria-live="polite" className="min-h-0">
        {state.error ? (
          <p className="flex items-start gap-1.5 text-label text-danger">
            <AlertCircle aria-hidden className="mt-px size-3.5 shrink-0" />
            <span>{state.error}</span>
          </p>
        ) : null}
      </div>

      <SubmitButton />
    </form>
  );
}

function SubmitButton() {
  // `useFormStatus` must be read from a component *inside* the form, which is the only reason
  // this is a separate component rather than a line in the one above.
  const { pending } = useFormStatus();
  return (
    <Button type="submit" variant="primary" className="w-full" disabled={pending}>
      {pending ? 'Signing in…' : 'Sign in'}
    </Button>
  );
}

function Field({
  label,
  name,
  ...props
}: { label: string; name: string } & React.InputHTMLAttributes<HTMLInputElement>) {
  const id = `login-${name}`;
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-label font-[510] text-fg">
        {label}
      </label>
      <input
        id={id}
        name={name}
        className="h-9 rounded-md bg-surface px-3 text-body text-fg ring-hairline outline-none transition-shadow duration-(--duration-fast) placeholder:text-fg-faint focus-visible:ring-2 focus-visible:ring-accent"
        {...props}
      />
    </div>
  );
}
