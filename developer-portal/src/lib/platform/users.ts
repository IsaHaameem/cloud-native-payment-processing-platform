import 'server-only';

import { env } from '@/lib/env';

/**
 * The portal's read of the signed-in user's own account (M23.4).
 *
 * ── Why this is a request and not a read of the session ───────────────────────────────
 *
 * The session cookie already carries `userId`, `email` and `roles`, and settings could render
 * those without a network call. It deliberately does not, for two reasons that only show up on
 * this screen:
 *
 * - The cookie is a **snapshot taken at sign-in**. `session.ts` says so explicitly and keeps
 *   display data out for exactly this reason. A user who verifies their email in another tab and
 *   then opens settings would be told they are unverified, by a cookie that was correct last
 *   Tuesday.
 * - `fullName`, `emailVerified` and `createdAt` are not in the session at all, and putting them
 *   there to save one request would grow a value sent on every request in the portal — including
 *   every static asset — to serve one page.
 *
 * ── Read-only, because identity-service is ────────────────────────────────────────────
 *
 * `UserController` exposes `GET /me` and an ADMIN-only list. There is no update endpoint, so the
 * account section renders and does not edit. That is stated on the screen rather than implied by
 * absent buttons: a settings page with an uneditable name and no explanation reads as broken.
 */

const TIMEOUT_MS = 10_000;

/** `UserResponse`, as much of it as the portal renders. */
export interface UserProfile {
  readonly id: string;
  readonly email: string;
  /** Optional at registration (`RegisterRequest.fullName` has no `@NotBlank`). */
  readonly fullName: string | undefined;
  readonly roles: readonly string[];
  readonly emailVerified: boolean;
  /** RFC 3339, or `undefined` when the platform did not send one. */
  readonly createdAt: string | undefined;
}

export type UserLookup =
  { readonly status: 'found'; readonly user: UserProfile } | { readonly status: 'unavailable' };

/**
 * @returns the account, or `unavailable`. Never throws.
 *
 * There is no `absent` case: the token was minted for this user, so a 404 here would mean the
 * account was deleted mid-session — indistinguishable, from the portal's side, from the platform
 * being unwell, and handled the same way.
 */
export async function fetchCurrentUser(accessToken: string): Promise<UserLookup> {
  let response: Response;
  try {
    response = await fetch(`${env.gatewayUrl}/api/v1/users/me`, {
      headers: { Authorization: `Bearer ${accessToken}`, Accept: 'application/json' },
      cache: 'no-store',
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    return { status: 'unavailable' };
  }

  if (!response.ok) return { status: 'unavailable' };

  let body: unknown;
  try {
    body = await response.json();
  } catch {
    return { status: 'unavailable' };
  }

  const user = userFrom(body);
  return user ? { status: 'found', user } : { status: 'unavailable' };
}

/**
 * Validates the wire shape before it becomes a `UserProfile`.
 *
 * `id` and `email` are required because a settings page that renders an empty identity is worse
 * than one that says it could not load: the first invites the user to trust it.
 */
function userFrom(value: unknown): UserProfile | undefined {
  if (typeof value !== 'object' || value === null) return undefined;
  const raw = value as Record<string, unknown>;

  if (typeof raw.id !== 'string' || typeof raw.email !== 'string') return undefined;
  if (raw.id.length === 0 || raw.email.length === 0) return undefined;

  return {
    id: raw.id,
    email: raw.email,
    // `null` is what Jackson writes for an unset column; the portal spells absence `undefined`
    // throughout (D194), so the two are reconciled here rather than at every reader.
    fullName:
      typeof raw.fullName === 'string' && raw.fullName.length > 0 ? raw.fullName : undefined,
    roles: Array.isArray(raw.roles)
      ? raw.roles.filter((r): r is string => typeof r === 'string')
      : [],
    emailVerified: raw.emailVerified === true,
    createdAt: typeof raw.createdAt === 'string' ? raw.createdAt : undefined,
  };
}
