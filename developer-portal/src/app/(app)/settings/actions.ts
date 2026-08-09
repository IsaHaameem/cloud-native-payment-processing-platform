'use server';

import {
  type UpdateFailure,
  updateMerchantProfile,
  updateMerchantWebhook,
} from '@/lib/platform/merchants';
import { GUARD_MESSAGES, guardFormRequest } from '@/lib/security/form-guard';
import { readSession } from '@/lib/session/require';

/**
 * Editing the merchant's own account details (M23.4).
 *
 * ── Why these are Server Actions and not the M23.3 read route ─────────────────────────
 *
 * `/api/platform/[operation]` resolves **GETs only**, deliberately (D207): a mutation reachable
 * from the browser by naming it would sit outside the CSRF token and the origin assertion that
 * M23.2 put on every state-changing request. These are mutations, so they go where mutations go —
 * a Server Action guarded by `guardFormRequest`, exactly like sign-in, sign-up and onboarding.
 *
 * ── The merchant is never named ───────────────────────────────────────────────────────
 *
 * Both endpoints are `/me`. The owner comes from the JWT subject at merchant-service, so there is
 * no id in the request for the portal to get wrong and no authorization decision for this file to
 * make beyond having a session. That is the whole of merchant isolation on this screen.
 *
 * ── The session is not rewritten ──────────────────────────────────────────────────────
 *
 * Onboarding reseals the cookie because it creates `merchantId`, which the session carries and
 * `requireMerchant` reads. Nothing here changes that: a business name is display data the session
 * deliberately does not hold (`session.ts`), and the shell re-reads it per render through
 * `currentMerchant`.
 *
 * ── Success reports which section saved; the form then navigates ─────────────────────
 *
 * Three designs were tried here and the first two failed *silently*, which is why the reasoning
 * is kept rather than summarised.
 *
 * `revalidatePath` refreshed the shell correctly and re-rendered the tree holding the form,
 * discarding `useActionState` and the confirmation with it: the value persisted every time while
 * the screen intermittently said nothing.
 *
 * `router.refresh()` kept the confirmation and did **not** reliably re-render the shared `(app)`
 * layout, so against the real stack the header went on showing the old business name after a
 * rename that had definitely succeeded.
 *
 * `redirect()` from the action was the obvious answer and does not navigate at all here — the
 * target differs from the current URL only in its query string, and the router treats that as
 * the page it is already on. Measured: the save persisted, the URL never changed, and nothing on
 * screen moved.
 *
 * So the action reports the section that saved and the form performs the navigation itself, to a
 * URL that genuinely differs. That re-renders page *and* layout on the server, and the
 * confirmation arrives as a query flag the page owns rather than as client state something can
 * discard. The cost is that the confirmation needs JavaScript — the save itself does not, and
 * this is a screen behind authentication rather than the front door M23.2 kept working without a
 * bundle.
 */

export interface SettingsState {
  readonly error: string | undefined;
  /** Which field the message belongs to, so the form can mark it rather than only announce it. */
  readonly field: 'businessName' | 'contactEmail' | 'webhookUrl' | undefined;
  /** The section that just saved, which the form turns into a navigation. */
  readonly saved: 'business' | 'callback' | undefined;
}

/*
 * The initial state lives with the forms, not here.
 *
 * A `'use server'` module may export **async functions only** — every other export is treated as
 * a callable the client could invoke, and Next refuses the file outright: *"A `use server` file
 * can only export async functions, found object."* Exporting a plain `IDLE` constant from here
 * compiled, passed the type checker, and broke the page at runtime the first time a Server Action
 * on it was invoked. See `settings-forms.tsx`.
 */

const MESSAGES = {
  name_required: 'Enter the name customers will see.',
  name_long: 'Use at most 200 characters.',
  email_invalid: 'Enter a valid email address.',
  email_long: 'Use at most 255 characters.',
  webhook_scheme: 'The URL must start with https://.',
  webhook_long: 'Use at most 2048 characters.',
  invalid: 'Those details were not accepted. Check them and try again.',
  unauthorized: 'Your session is no longer valid. Sign in again to continue.',
  absent: 'This account does not have a business profile yet.',
  unavailable: 'Settings are temporarily unavailable. Please try again in a moment.',
} as const;

/** `UpdateMerchantRequest` / `UpdateWebhookRequest` bounds, restated so a round trip is not spent. */
const NAME_MAX = 200;
const EMAIL_MAX = 255;
const WEBHOOK_MAX = 2048;

/** Deliberately permissive: merchant-service's `@Email` is authoritative. */
const EMAIL_SHAPE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

function failureMessage(reason: UpdateFailure): string {
  return MESSAGES[reason];
}

export async function updateBusinessAction(
  _previous: SettingsState,
  formData: FormData,
): Promise<SettingsState> {
  const refused = await guardFormRequest(formData.get('csrfToken'));
  if (refused) return { error: GUARD_MESSAGES[refused], field: undefined, saved: undefined };

  const session = await readSession();
  if (!session) return { error: MESSAGES.unauthorized, field: undefined, saved: undefined };

  const businessName = String(formData.get('businessName') ?? '').trim();
  const contactEmail = String(formData.get('contactEmail') ?? '')
    .trim()
    .toLowerCase();

  if (businessName.length === 0) {
    return { error: MESSAGES.name_required, field: 'businessName', saved: undefined };
  }
  if (businessName.length > NAME_MAX) {
    return { error: MESSAGES.name_long, field: 'businessName', saved: undefined };
  }
  if (!EMAIL_SHAPE.test(contactEmail)) {
    return { error: MESSAGES.email_invalid, field: 'contactEmail', saved: undefined };
  }
  if (contactEmail.length > EMAIL_MAX) {
    return { error: MESSAGES.email_long, field: 'contactEmail', saved: undefined };
  }

  const result = await updateMerchantProfile(session.accessToken, { businessName, contactEmail });
  if (!result.ok) {
    return { error: failureMessage(result.reason), field: undefined, saved: undefined };
  }

  return { error: undefined, field: undefined, saved: 'business' };
}

export async function updateWebhookAction(
  _previous: SettingsState,
  formData: FormData,
): Promise<SettingsState> {
  const refused = await guardFormRequest(formData.get('csrfToken'));
  if (refused) return { error: GUARD_MESSAGES[refused], field: undefined, saved: undefined };

  const session = await readSession();
  if (!session) return { error: MESSAGES.unauthorized, field: undefined, saved: undefined };

  const raw = String(formData.get('webhookUrl') ?? '').trim();

  // Blank clears it. `UpdateWebhookRequest` documents that, and `Merchant.updateWebhookUrl`
  // normalises blank to null — so an empty field is a deliberate instruction, not a validation
  // failure, and the form says as much.
  const webhookUrl = raw.length === 0 ? null : raw;

  if (webhookUrl !== null) {
    if (!webhookUrl.startsWith('https://')) {
      return { error: MESSAGES.webhook_scheme, field: 'webhookUrl', saved: undefined };
    }
    if (webhookUrl.length > WEBHOOK_MAX) {
      return { error: MESSAGES.webhook_long, field: 'webhookUrl', saved: undefined };
    }
  }

  const result = await updateMerchantWebhook(session.accessToken, webhookUrl);
  if (!result.ok) {
    return { error: failureMessage(result.reason), field: 'webhookUrl', saved: undefined };
  }

  return { error: undefined, field: undefined, saved: 'callback' };
}
