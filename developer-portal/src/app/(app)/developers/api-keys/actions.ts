'use server';

import {
  type IssuedApiKey,
  type KeyFailure,
  type KeyType,
  createApiKey,
  listApiKeys,
  revokeApiKey,
  rotateApiKey,
} from '@/lib/platform/api-keys';
import { GUARD_MESSAGES, guardFormRequest } from '@/lib/security/form-guard';
import type { Mode } from '@/lib/session/session';
import { readSession } from '@/lib/session/require';

/**
 * Creating, rotating and revoking API keys (M23.5).
 *
 * ── Where the secret goes, and everywhere it does not ─────────────────────────────────
 *
 * A raw key exists in readable form for exactly one round trip. merchant-service stores only its
 * SHA-256 hash, so the value in the creation response is the only copy that will ever exist — and
 * these actions hand it to the caller and keep nothing.
 *
 * It travels back inside the Server Action's own response, which is a same-origin POST result.
 * That is the whole of its exposure, and it is the minimum possible: the alternatives all make it
 * worse. A redirect with the secret in the query string writes it to browser history, the
 * `Referer` of every subsequent request, and any access log on the path. Stashing it in the
 * session cookie puts a live credential at rest in something the browser stores. Re-rendering it
 * from the server on the next page load is impossible by design, and building anything that could
 * would mean keeping it somewhere.
 *
 * So the secret is returned in the action state, shown once, and lost when the dialog closes.
 * `page.tsx` cannot show it — it reads `ApiKeyResponse`, which carries no secret — and a reload
 * therefore ends the reveal permanently. That is D114 and §4.9 implemented literally, not
 * approximated.
 *
 * ── Every one of these is a sensitive mutation ────────────────────────────────────────
 *
 * All three guard on `guardFormRequest` before anything else, which is M23.2's CSRF token *and*
 * its origin assertion. They are Server Actions rather than anything reachable by naming an
 * operation, for the reason D207 gives: `/api/platform/[operation]` resolves GETs only, precisely
 * so that minting and destroying credentials cannot be reached that way.
 *
 * ── The merchant is never named ───────────────────────────────────────────────────────
 *
 * Creation posts to `/me/api-keys`; rotation and revocation carry a key id that merchant-service
 * resolves with `findByIdAndMerchantId`. A key belonging to another merchant is *not found* — so
 * the isolation boundary is enforced where the data is, and nothing here has an authorization
 * decision to make beyond holding a session.
 */

export interface KeyActionState {
  readonly error: string | undefined;
  /** Which field the message belongs to, when it belongs to one. */
  readonly field: 'name' | 'confirmation' | undefined;
  /**
   * The freshly issued secret, present only in the response that created it.
   *
   * It is never read back from anywhere; the client shows it and drops it.
   */
  readonly issued: IssuedApiKey | undefined;
  /** A completed action with nothing to reveal — the client turns this into a reload. */
  readonly done: 'revoked' | undefined;
}

const IDLE_ERRORS = {
  name_long: 'Use at most 100 characters.',
  type_invalid: 'Choose whether this key is publishable or secret.',
  mode_invalid: 'Choose test or live.',
  scopes_invalid: 'Choose at least one permission.',
  confirmation_mismatch: 'The name does not match. Type it exactly to confirm.',
  invalid: 'That request was refused. Check the details and try again.',
  unauthorized: 'Your session is no longer valid. Sign in again to continue.',
  absent: 'That key no longer exists. Reload to see the current list.',
  unavailable: 'Key management is temporarily unavailable. Please try again in a moment.',
} as const;

/** `CreateApiKeyRequest.name` is `@Size(max = 100)`, restated so a round trip is not spent. */
const NAME_MAX = 100;

/**
 * The permissions a key may be given, and the only values the portal will send.
 *
 * merchant-service does not validate scopes — `CreateApiKeyRequest.scopes` is a free `List<String>`
 * — so the allow-list is enforced here rather than assumed. That is the difference between a
 * picker and a text box that happens to look like one: a scope the platform does not understand
 * would be stored, displayed, and silently grant nothing.
 */
const SCOPE_CHOICES = ['*', 'payments:read', 'payments:write', 'refunds:write'] as const;

function refusal(reason: KeyFailure): KeyActionState {
  return { error: IDLE_ERRORS[reason], field: undefined, issued: undefined, done: undefined };
}

function problem(message: string, field: KeyActionState['field']): KeyActionState {
  return { error: message, field, issued: undefined, done: undefined };
}

export async function createKeyAction(
  _previous: KeyActionState,
  formData: FormData,
): Promise<KeyActionState> {
  const refused = await guardFormRequest(formData.get('csrfToken'));
  if (refused) return problem(GUARD_MESSAGES[refused], undefined);

  const session = await readSession();
  if (!session) return refusal('unauthorized');

  const name = String(formData.get('name') ?? '').trim();
  if (name.length > NAME_MAX) return problem(IDLE_ERRORS.name_long, 'name');

  const rawType = String(formData.get('type') ?? '');
  if (rawType !== 'PUBLISHABLE' && rawType !== 'SECRET') {
    return problem(IDLE_ERRORS.type_invalid, undefined);
  }
  const type: KeyType = rawType;

  const rawMode = String(formData.get('mode') ?? '');
  if (rawMode !== 'test' && rawMode !== 'live') return problem(IDLE_ERRORS.mode_invalid, undefined);
  const mode: Mode = rawMode;

  const scopes = formData
    .getAll('scopes')
    .map(String)
    .filter((scope): scope is (typeof SCOPE_CHOICES)[number] =>
      (SCOPE_CHOICES as readonly string[]).includes(scope),
    );
  if (scopes.length === 0) return problem(IDLE_ERRORS.scopes_invalid, undefined);

  const result = await createApiKey(session.accessToken, {
    // Blank is allowed: merchant-service names an unnamed key after its type and mode, which is a
    // better default than anything the portal could invent, and matches the four starter keys.
    name,
    type,
    mode,
    scopes,
  });
  if (!result.ok) return refusal(result.reason);

  /*
   * The session's mode is **not** changed here, and the reason is a defect this action had.
   *
   * §6.3 asks for a mode selector on creation and the list is scoped to the session's mode, so a
   * key minted in the other mode lands in a list that does not show it. The obvious fix was to
   * reseal the session to the new mode right here. It cost the secret.
   *
   * Measured against the real stack, and reproducible every time: the reseal succeeded (logged on
   * both sides), merchant-service stored the key, the action's response came back 200, and the
   * page re-rendered to completion under the new mode — layout, session, listing, all logged. The
   * reveal never appeared. The form sat on "Creating…" indefinitely while a key existed whose
   * secret was already unrecoverable. Removing the reseal fixes it; restoring it breaks it again.
   *
   * The mechanism is the one M23.4 already paid for: setting a cookie makes Next re-render this
   * route inside the action's own response, and that re-render lands on the tree holding the
   * `useActionState` which is waiting for this return value. Under a *changed* mode that tree is
   * not the same tree, so the state waiting for the result does not survive to receive it.
   *
   * That is precisely the failure M23.4 recorded for `revalidatePath` (D210), arriving by a
   * different route: **an action must not re-render the tree that is waiting for its result.**
   *
   * So the mode change moves to *after* the secret is safely copied — the reveal's acknowledge
   * button submits the portal's own `POST /api/session/mode`, which is where mode has always been
   * changed (D184) and which redirects back here as a fresh document. One mechanism, one source of
   * truth, and nothing re-renders while a secret is on screen.
   */
  return { error: undefined, field: undefined, issued: result.key, done: undefined };
}

/**
 * Rotates a key, revealing the replacement's secret once.
 *
 * There is no confirmation typing here, and that is a judgement rather than an omission: rotation
 * is the *recoverable* destructive action. The old key keeps working for its grace window, which
 * is the entire point of the operation, so the cost of an accidental rotation is a secret to
 * redeploy rather than an outage. Revocation gets the friction because revocation is immediate.
 */
export async function rotateKeyAction(
  _previous: KeyActionState,
  formData: FormData,
): Promise<KeyActionState> {
  const refused = await guardFormRequest(formData.get('csrfToken'));
  if (refused) return problem(GUARD_MESSAGES[refused], undefined);

  const session = await readSession();
  if (!session) return refusal('unauthorized');

  const keyId = String(formData.get('keyId') ?? '');
  if (keyId.length === 0) return refusal('invalid');

  const result = await rotateApiKey(session.accessToken, keyId);
  if (!result.ok) return refusal(result.reason);

  return { error: undefined, field: undefined, issued: result.key, done: undefined };
}

/**
 * Revokes a key, once the user has typed its name back.
 *
 * ── The name is checked against what the platform stores, not against the form ────────
 *
 * The obvious implementation carries the expected name in a hidden field and compares the two
 * halves of one submission. That is theatre: both sides come from the same request, so it confirms
 * only that the client agrees with itself, and a request that skipped the dialog entirely would
 * pass by sending two matching blanks.
 *
 * So the key is re-read from merchant-service and the typed text is compared to the **stored**
 * name. The cost is one extra call on the rarest and most destructive action on the screen, and it
 * buys two things: the confirmation becomes a property of the request rather than of the UI that
 * usually produces it, and a key that has already been revoked or never belonged to this merchant
 * is reported as absent instead of being revoked again.
 *
 * The comparison is trimmed but case-sensitive. "Prod" and "prod" are two different keys, and this
 * friction exists precisely to make someone read what they typed.
 */
export async function revokeKeyAction(
  _previous: KeyActionState,
  formData: FormData,
): Promise<KeyActionState> {
  const refused = await guardFormRequest(formData.get('csrfToken'));
  if (refused) return problem(GUARD_MESSAGES[refused], undefined);

  const session = await readSession();
  if (!session) return refusal('unauthorized');

  const keyId = String(formData.get('keyId') ?? '');
  const typed = String(formData.get('confirmation') ?? '').trim();
  if (keyId.length === 0) return refusal('invalid');

  const listing = await listApiKeys(session.accessToken);
  if (listing.status !== 'found') return refusal('unavailable');

  // Not found means not this merchant's — merchant-service resolves keys by (id, merchantId), and
  // the listing is scoped the same way, so this cannot confirm that someone else's id exists.
  const target = listing.keys.find((key) => key.id === keyId);
  if (!target) return refusal('absent');

  if (typed !== target.name.trim())
    return problem(IDLE_ERRORS.confirmation_mismatch, 'confirmation');

  const result = await revokeApiKey(session.accessToken, keyId);
  if (!result.ok) return refusal(result.reason);

  return { error: undefined, field: undefined, issued: undefined, done: 'revoked' };
}
