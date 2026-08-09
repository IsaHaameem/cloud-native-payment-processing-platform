'use client';

import { LogOut, User } from 'lucide-react';

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { CSRF_FIELD } from '@/lib/security/csrf-field';
import { submitById } from '@/lib/security/submit';

/**
 * The account menu, and the portal's only way out (M23.2).
 *
 * ── Sign out is a form post, not a link ───────────────────────────────────────────────
 *
 * A real `<form method="post">` carrying the CSRF token. A link would make sign-out a `GET`,
 * which any third-party page could trigger with an `<img>` tag — a small harm that is nonetheless
 * a harm, and the reason `/logout` exports no `GET` handler at all.
 *
 * ── Why the form is outside the menu, and submitted by hand ───────────────────────────
 *
 * Both parts of that were measured rather than assumed, and both arrangements that seem more
 * natural are broken:
 *
 * - With the `<form>` *wrapped around* the menu item, clicking did nothing: Radix closes the menu
 *   on select, unmounting the content — and the form with it — before the browser processes the
 *   submission.
 * - With the form outside and the button carrying `type="submit" form="…"`, clicking still did
 *   nothing, and produced no request whatsoever. Radix unmounts the button as part of selecting
 *   it, so the click's default action never runs.
 *
 * So the item submits the form itself, from `onSelect`, before the menu tears itself down. That
 * costs the no-JavaScript path — but only nominally: this control lives inside a dropdown that
 * cannot be opened without JavaScript in the first place, so there was never a no-JS route to it.
 * The form element still earns its place by carrying the token and the method, so the request
 * that leaves the browser is an ordinary, guarded form post.
 *
 * ── The identity is the client's whole share of the session ───────────────────────────
 *
 * Rendered from `PublicSession` plus a business name the layout looked up separately. Neither
 * can carry a token by construction. This component is the reason `PublicSession` exists: it is
 * the first client component in the portal that displays anything about the signed-in user, and
 * the boundary had to be somewhere it could not be crossed by accident.
 *
 * The business name sits above the email because it answers the question a merchant with two
 * accounts actually has — *which business am I acting as* — and the email only says which login
 * they used. It is absent for a user who has not onboarded, which is a state this menu can be
 * rendered in.
 */

const LOGOUT_FORM_ID = 'pf-logout-form';

export function AccountMenu({
  email,
  businessName,
  csrfToken,
}: {
  email: string;
  businessName?: string | undefined;
  csrfToken: string;
}) {
  return (
    <>
      {/* Hidden, but in the document: it is what `submitById` submits. */}
      <form id={LOGOUT_FORM_ID} action="/logout" method="post" hidden>
        <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
      </form>

      <DropdownMenu>
        <DropdownMenuTrigger
          aria-label="Account"
          className="flex size-7 items-center justify-center rounded-md text-fg-subtle transition-colors duration-(--duration-fast) hover:bg-surface-hover hover:text-fg"
        >
          <User className="size-4" />
        </DropdownMenuTrigger>

        <DropdownMenuContent align="end" className="min-w-56">
          <DropdownMenuLabel>
            {businessName ? <span className="block truncate text-fg">{businessName}</span> : null}
            <span className="block truncate font-normal text-fg-subtle">{email}</span>
          </DropdownMenuLabel>
          <DropdownMenuSeparator />

          <DropdownMenuItem onSelect={() => submitById(LOGOUT_FORM_ID)}>
            <LogOut />
            Sign out
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
}
