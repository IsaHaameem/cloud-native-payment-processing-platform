'use client';

import { useActionState } from 'react';
import { useFormStatus } from 'react-dom';

import { AuthField } from '@/components/auth/auth-field';
import { FormAlert } from '@/components/auth/form-alert';
import { Button } from '@/components/ui/button';
import { CSRF_FIELD } from '@/lib/security/csrf-field';

import { type ResetPasswordState, resetPasswordAction } from './actions';

/**
 * The new-password form (M23.2b).
 *
 * ── Why there is a confirmation field here and not on signup ──────────────────────────
 *
 * Signup can afford one password field: get it wrong and the very next screen asks you to sign
 * in, so the mistake surfaces in seconds and costs a click. A reset consumes a single-use token,
 * so a mistyped password is discovered at the *next* sign-in, by which time the link is spent and
 * the only route back is another email. The asymmetry in the cost of the error is the whole
 * argument, and it is why the two forms differ.
 *
 * `autoComplete="new-password"` on both, so a password manager offers to generate and then to
 * store rather than autofilling the old one it has on file.
 */
export function ResetPasswordForm({ csrfToken, token }: { csrfToken: string; token: string }) {
  const [state, formAction] = useActionState<ResetPasswordState, FormData>(resetPasswordAction, {
    error: undefined,
    field: undefined,
  });

  const fieldError = (field: ResetPasswordState['field']) =>
    state.field === field ? state.error : undefined;

  return (
    <form action={formAction} className="flex flex-col gap-3.5">
      <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
      {/*
       * The reset token, round-tripped through the form rather than held server-side. A hidden
       * field travels with a native submission, so this works with the client runtime absent —
       * the property the whole entry flow is built for.
       */}
      <input type="hidden" name="token" value={token} />

      <AuthField
        label="New password"
        name="password"
        hint="8–72 characters"
        type="password"
        autoComplete="new-password"
        placeholder="••••••••••••"
        minLength={8}
        maxLength={72}
        required
        reveal
        autoFocus
        error={fieldError('password')}
      />
      <AuthField
        label="Confirm new password"
        name="confirm"
        type="password"
        autoComplete="new-password"
        placeholder="••••••••••••"
        minLength={8}
        maxLength={72}
        required
        reveal
        error={fieldError('confirm')}
      />

      <FormAlert>{state.field === undefined ? state.error : undefined}</FormAlert>

      <SubmitButton />
    </form>
  );
}

function SubmitButton() {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" variant="primary" size="lg" className="w-full" disabled={pending}>
      {pending ? 'Setting password…' : 'Set new password'}
    </Button>
  );
}
