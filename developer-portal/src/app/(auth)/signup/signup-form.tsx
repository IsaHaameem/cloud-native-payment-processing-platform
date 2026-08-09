'use client';

import { useActionState } from 'react';
import { useFormStatus } from 'react-dom';

import { AuthField } from '@/components/auth/auth-field';
import { FormAlert } from '@/components/auth/form-alert';
import { Button } from '@/components/ui/button';
import { CSRF_FIELD } from '@/lib/security/csrf-field';

import { type SignupState, signupAction } from './actions';

/**
 * The account-creation form (M23.2a).
 *
 * Built exactly like the sign-in form and for the same reason: a plain `<form action={…}>`
 * bound to a Server Action, so it submits and redirects with no JavaScript at all. The two
 * screens the product's front door is made of are the two that must not depend on a bundle
 * having loaded.
 *
 * ── The fields are the contract, and no more ──────────────────────────────────────────
 *
 * `RegisterRequest` is `(email, password, fullName)`, and `fullName` carries no `@NotBlank` —
 * so it is presented as optional rather than demanded, and the action omits it from the body
 * when it is blank. Nothing else is collected: a signup form asking for a company, a country or
 * a phone number would be collecting data the platform has nowhere to put. The business details
 * belong to onboarding, which is where merchant-service actually accepts them.
 *
 * ── The password rules are stated up front ────────────────────────────────────────────
 *
 * 8 to 72 characters — the platform's own bounds, 72 being BCrypt's input limit. Shown beside
 * the label before the field is touched, because a rule discovered by breaking it is a rule the
 * form failed to communicate. `minLength`/`maxLength` let the browser say so without a round
 * trip, and the action re-checks server-side regardless.
 */
export function SignupForm({ csrfToken, next }: { csrfToken: string; next: string }) {
  const [state, formAction] = useActionState<SignupState, FormData>(signupAction, {
    error: undefined,
    field: undefined,
  });

  const fieldError = (field: SignupState['field']) =>
    state.field === field ? state.error : undefined;

  return (
    <form action={formAction} className="flex flex-col gap-3.5">
      <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
      <input type="hidden" name="next" value={next} />

      <AuthField
        label="Full name"
        name="fullName"
        hint="Optional"
        type="text"
        autoComplete="name"
        placeholder="Ada Lovelace"
        maxLength={150}
        autoFocus
      />
      <AuthField
        label="Work email"
        name="email"
        type="email"
        autoComplete="username"
        placeholder="you@company.com"
        maxLength={255}
        required
        error={fieldError('email')}
      />
      <AuthField
        label="Password"
        name="password"
        hint="8–72 characters"
        type="password"
        autoComplete="new-password"
        placeholder="••••••••••••"
        minLength={8}
        maxLength={72}
        required
        reveal
        error={fieldError('password')}
      />

      {/* Field-attached messages are rendered on the field; anything else lands here. */}
      <FormAlert>{state.field === undefined ? state.error : undefined}</FormAlert>

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
      {pending ? 'Creating account…' : 'Create account'}
    </Button>
  );
}
