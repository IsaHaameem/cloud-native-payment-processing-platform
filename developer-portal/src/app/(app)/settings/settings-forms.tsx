'use client';

import { useActionState, useEffect, useState } from 'react';
import { useFormStatus } from 'react-dom';

import { AuthField } from '@/components/auth/auth-field';
import { FormAlert } from '@/components/auth/form-alert';
import { Button } from '@/components/ui/button';
import { CSRF_FIELD } from '@/lib/security/csrf-field';

import { type SettingsState, updateBusinessAction, updateWebhookAction } from './actions';

/**
 * The two editable sections of settings (M23.4).
 *
 * ── Separate forms, separate saves ────────────────────────────────────────────────────
 *
 * One form over all three fields would be fewer components and the wrong shape: the business
 * profile and the callback URL are two different endpoints (`PATCH /me` and `PATCH /me/webhook`),
 * so a single submit would be two requests that can half fail — and the screen would have to
 * explain which half.
 *
 * ── A save ends in a navigation, not in a piece of client state ──────────────────────
 *
 * The action reports which section saved; `useSavedNavigation` turns that into a `router.replace`
 * to `/settings?saved=…`. That re-renders the page **and the shared layout** on the server, so the
 * header picks up a renamed business, and the confirmation arrives as a query flag the page owns.
 * `actions.ts` records the three designs this went through and what each earlier one silently
 * lost.
 *
 * ── The save button is disabled until something changes ───────────────────────────────
 *
 * Not decoration: `PATCH /me` replaces both fields, so an accidental submit of an untouched form
 * is a write. Disabling it until an edit happens makes the no-op impossible rather than merely
 * harmless, and it is the clearest signal that what is on screen *is* what is stored.
 */

const IDLE: SettingsState = { error: undefined, field: undefined, saved: undefined };

/**
 * Turns a successful save into a full document navigation.
 *
 * ── Why a document load and not a router navigation ──────────────────────────────────
 *
 * The header renders the business name, and it lives in the shared `(app)` layout. Next
 * deliberately **preserves layouts** across a navigation that stays within them — that is what a
 * layout is for — so a `router.replace` to `/settings?saved=…` re-rendered the page and left the
 * header exactly as it was. Measured against the real stack: the banner appeared, the value was
 * stored, and the chrome above it went on showing the old name.
 *
 * Everything cheaper was tried first and is recorded in `actions.ts`: `revalidatePath` destroyed
 * the confirmation, `router.refresh()` did not reliably re-render the layout, and `redirect()` to
 * the same pathname did not navigate at all.
 *
 * So this reloads. A settings save is a rare, deliberate action — one document load is a fair
 * price for a screen that is entirely consistent afterwards, and it is exactly what a plain HTML
 * form post would have done.
 */
function useSavedNavigation(saved: SettingsState['saved']) {
  useEffect(() => {
    if (saved) window.location.assign(`/settings?saved=${saved}`);
  }, [saved]);
}

function SubmitButton({ label, disabled }: { label: string; disabled: boolean }) {
  const { pending } = useFormStatus();
  return (
    <Button type="submit" variant="primary" disabled={pending || disabled}>
      {pending ? 'Saving…' : label}
    </Button>
  );
}

export function BusinessProfileForm({
  csrfToken,
  businessName,
  contactEmail,
}: {
  csrfToken: string;
  businessName: string;
  contactEmail: string;
}) {
  const [state, formAction] = useActionState<SettingsState, FormData>(updateBusinessAction, IDLE);
  const [dirty, setDirty] = useState(false);
  useSavedNavigation(state.saved);

  return (
    <form action={formAction} onChange={() => setDirty(true)} className="flex flex-col gap-3.5">
      <input type="hidden" name={CSRF_FIELD} value={csrfToken} />

      <AuthField
        label="Business name"
        name="businessName"
        hint="Shown to your customers"
        type="text"
        autoComplete="organization"
        defaultValue={businessName}
        maxLength={200}
        required
        error={state.field === 'businessName' ? state.error : undefined}
      />
      <AuthField
        label="Contact email"
        name="contactEmail"
        hint="Where we reach you about payments"
        type="email"
        autoComplete="email"
        defaultValue={contactEmail}
        maxLength={255}
        required
        error={state.field === 'contactEmail' ? state.error : undefined}
      />

      <FormAlert>{state.field === undefined ? state.error : undefined}</FormAlert>

      <div>
        <SubmitButton label="Save changes" disabled={!dirty} />
      </div>
    </form>
  );
}

export function WebhookUrlForm({
  csrfToken,
  webhookUrl,
}: {
  csrfToken: string;
  webhookUrl: string | undefined;
}) {
  const [state, formAction] = useActionState<SettingsState, FormData>(updateWebhookAction, IDLE);
  const [dirty, setDirty] = useState(false);
  useSavedNavigation(state.saved);

  return (
    <form action={formAction} onChange={() => setDirty(true)} className="flex flex-col gap-3.5">
      <input type="hidden" name={CSRF_FIELD} value={csrfToken} />

      <AuthField
        label="Callback URL"
        name="webhookUrl"
        hint="https:// only — leave empty to clear"
        type="url"
        inputMode="url"
        placeholder="https://api.your-company.com/paymentflow"
        defaultValue={webhookUrl ?? ''}
        maxLength={2048}
        error={state.field === 'webhookUrl' ? state.error : undefined}
      />

      <FormAlert>{state.field === undefined ? state.error : undefined}</FormAlert>

      <div>
        <SubmitButton label="Save callback URL" disabled={!dirty} />
      </div>
    </form>
  );
}
