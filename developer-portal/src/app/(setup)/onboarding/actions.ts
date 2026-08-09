'use server';

import { redirect } from 'next/navigation';

import { lookupMerchant, onboardMerchant } from '@/lib/platform/merchants';
import { GUARD_MESSAGES, guardFormRequest } from '@/lib/security/form-guard';
import { DEFAULT_AFTER_LOGIN } from '@/lib/security/redirect';
import { persistSession } from '@/lib/session/lifecycle';
import { readSession } from '@/lib/session/require';

/**
 * Creating the signed-in user's merchant (M23.2a).
 *
 * ── Why this rewrites the session cookie ──────────────────────────────────────────────
 *
 * `merchantId` is sealed into the session at sign-in and read from there on every request, so
 * that `requireMerchant` costs nothing (see `lib/session/merchant.ts` for why it is resolved once
 * rather than per request). Onboarding is the single event that changes the answer. If it did not
 * write a fresh cookie the user would complete the form, be redirected to `/dashboard`, and be
 * bounced straight back here by a guard reading a session that still says they have no merchant —
 * a loop with no error anywhere to explain it.
 *
 * A Server Action can set cookies, which is what makes this possible at all; a Server Component
 * could not, and that constraint is the same one that put refresh in the middleware (D198).
 *
 * ── A 409 is a success this action has already had ───────────────────────────────────
 *
 * `MerchantService.onboard` refuses a second merchant for one owner. Reaching that means the user
 * has a merchant and a session that does not know it — a double submission, a stale tab, or an
 * onboarding whose cookie write was lost. Reporting "already exists" as an error would strand
 * them on a form they can never complete, so the merchant is re-read, sealed, and the user
 * continues. The alternative is a support ticket for a state the portal can resolve by itself.
 */

export interface OnboardingState {
  readonly error: string | undefined;
  readonly field: 'businessName' | 'contactEmail' | undefined;
}

const MESSAGES = {
  name_required: 'Enter the name customers will see.',
  name_long: 'Use at most 200 characters.',
  email_invalid: 'Enter a valid email address.',
  email_long: 'Use at most 255 characters.',
  invalid: 'Those details were not accepted. Check them and try again.',
  unauthorized: 'Your session is no longer valid. Sign in again to continue.',
  unavailable: 'Setup is temporarily unavailable. Please try again in a moment.',
} as const;

/** `OnboardMerchantRequest`'s own bounds — `@Size(max = 200)` and `@Size(max = 255)`. */
const NAME_MAX = 200;
const EMAIL_MAX = 255;

/** Deliberately permissive: merchant-service's `@Email` is authoritative. */
const EMAIL_SHAPE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

export async function onboardingAction(
  _previous: OnboardingState,
  formData: FormData,
): Promise<OnboardingState> {
  const refused = await guardFormRequest(formData.get('csrfToken'));
  if (refused) return { error: GUARD_MESSAGES[refused], field: undefined };

  const session = await readSession();
  // Not `requireSession`: a redirect thrown from inside an action is served to a `fetch` the
  // client runtime made, and the no-JavaScript path would see it too. Sending them to sign in
  // explicitly is the same outcome, stated.
  if (!session) redirect('/login?next=%2Fonboarding');

  const businessName = String(formData.get('businessName') ?? '').trim();
  const contactEmail = String(formData.get('contactEmail') ?? '')
    .trim()
    .toLowerCase();

  if (businessName.length === 0) return { error: MESSAGES.name_required, field: 'businessName' };
  if (businessName.length > NAME_MAX) return { error: MESSAGES.name_long, field: 'businessName' };
  if (!EMAIL_SHAPE.test(contactEmail)) {
    return { error: MESSAGES.email_invalid, field: 'contactEmail' };
  }
  if (contactEmail.length > EMAIL_MAX) return { error: MESSAGES.email_long, field: 'contactEmail' };

  const result = await onboardMerchant(session.accessToken, { businessName, contactEmail });

  let merchantId: string;
  if (result.ok) {
    merchantId = result.merchant.id;
  } else if (result.reason === 'already_exists') {
    const existing = await lookupMerchant(session.accessToken);
    // If even the re-read fails, the session cannot be corrected here — report unavailability
    // rather than sealing a merchant id that was never confirmed.
    if (existing.status !== 'found') return { error: MESSAGES.unavailable, field: undefined };
    merchantId = existing.merchant.id;
  } else if (result.reason === 'invalid') {
    return { error: MESSAGES.invalid, field: undefined };
  } else if (result.reason === 'unauthorized') {
    return { error: MESSAGES.unauthorized, field: undefined };
  } else {
    return { error: MESSAGES.unavailable, field: undefined };
  }

  /*
   * The merchant exists at the platform either way by this point, so a cookie write that fails
   * must not be reported as an onboarding failure — the user would be invited to create a second
   * merchant they cannot have. The redirect happens regardless; `/dashboard`'s guard re-checks,
   * and the worst case is one more trip through this page rather than a lost merchant.
   */
  await persistSession({ ...session, merchantId });

  redirect(DEFAULT_AFTER_LOGIN);
}
