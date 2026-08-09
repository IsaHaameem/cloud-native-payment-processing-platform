'use client';

import { ArrowRight } from 'lucide-react';
import { useActionState } from 'react';
import { useFormStatus } from 'react-dom';

import { AuthField } from '@/components/auth/auth-field';
import { FormAlert } from '@/components/auth/form-alert';
import { Button } from '@/components/ui/button';
import { CSRF_FIELD } from '@/lib/security/csrf-field';

import { type OnboardingState, onboardingAction } from './actions';

/**
 * The merchant-setup form (M23.2a).
 *
 * ── Two fields, because the contract has two ──────────────────────────────────────────
 *
 * `OnboardMerchantRequest` is `(businessName, contactEmail)` and nothing else. A setup *wizard*
 * with steps for address, industry, payout account and tax details is what this screen looks
 * like in a mature product, and every one of those steps would be collecting data
 * merchant-service has no column for. The platform's position is that onboarding is immediate
 * and unblocked — no KYC, no documents, no waiting — so the form is short because the product is,
 * not because the screen is unfinished.
 *
 * The contact email is pre-filled from the signed-in account, since for a self-serve signup they
 * are almost always the same address. It stays editable: the address a customer should reply to
 * is a business decision, not an identity one.
 */
export function OnboardingForm({
  csrfToken,
  suggestedEmail,
}: {
  csrfToken: string;
  suggestedEmail: string;
}) {
  const [state, formAction] = useActionState<OnboardingState, FormData>(onboardingAction, {
    error: undefined,
    field: undefined,
  });

  const fieldError = (field: OnboardingState['field']) =>
    state.field === field ? state.error : undefined;

  return (
    <form action={formAction} className="flex flex-col gap-3.5">
      <input type="hidden" name={CSRF_FIELD} value={csrfToken} />

      <AuthField
        label="Business name"
        name="businessName"
        type="text"
        autoComplete="organization"
        placeholder="Ada Lovelace Ltd"
        maxLength={200}
        required
        autoFocus
        error={fieldError('businessName')}
      />
      <AuthField
        label="Contact email"
        name="contactEmail"
        hint="Where we reach you about payments"
        type="email"
        autoComplete="email"
        placeholder="billing@company.com"
        defaultValue={suggestedEmail}
        maxLength={255}
        required
        error={fieldError('contactEmail')}
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
      {pending ? 'Creating your account…' : 'Create business account'}
      {pending ? null : <ArrowRight />}
    </Button>
  );
}
