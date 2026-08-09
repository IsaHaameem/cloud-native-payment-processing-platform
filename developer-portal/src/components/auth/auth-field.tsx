'use client';

import { Eye, EyeOff } from 'lucide-react';
import * as React from 'react';

import { cn } from '@/lib/utils';

/**
 * One labelled field on an entry page (M23.2a).
 *
 * Shared by sign-in and sign-up rather than written twice, because the two forms sit one link
 * apart and any drift between them — a different height, a different focus ring, a label in a
 * different weight — is visible in the two seconds it takes to move between them.
 *
 * ── Why not `components/ui/input` ─────────────────────────────────────────────────────
 *
 * That primitive is 32px tall and sits on `surface-inset`, tuned for dense product forms where a
 * dozen fields share a panel. An entry page has three, and they are the only thing on the screen;
 * they take the 36px height the large button uses so the field and the submit button below it
 * agree. The ring, radius, colours and transition are the same tokens either way, so this is a
 * different size in one system rather than a second system.
 *
 * ── The reveal toggle is a button, not a checkbox ─────────────────────────────────────
 *
 * It changes the state of a control rather than recording a preference, and it is reachable by
 * keyboard in reading order. `aria-pressed` carries the state, and the accessible name changes
 * with it — a screen reader user is told whether the password is currently visible, which an
 * icon alone cannot say.
 *
 * It is rendered unconditionally rather than only once the field has content. Hiding it while
 * empty is the tidier design and it breaks on autofill: a password manager fills the field
 * without firing the events a "does it have content" heuristic watches, so the control the user
 * most wants at that moment — *did it fill the right one?* — is the one that is missing.
 */
export function AuthField({
  label,
  name,
  hint,
  hintNode,
  error,
  reveal = false,
  ...props
}: {
  label: string;
  name: string;
  /** Quiet helper text, rendered beside the label. For stating a rule before it is broken. */
  hint?: string | undefined;
  /**
   * An interactive companion in the same slot — sign-in's "Forgot password?".
   *
   * Separate from `hint` rather than widening it to `ReactNode`, because the two behave
   * differently where it matters: `hint` is described text and is wired into the input's
   * `aria-describedby`, while this is a control of its own with its own accessible name. Reading
   * a link out as the field's description would announce "Forgot password?" every time focus
   * lands on the password box.
   */
  hintNode?: React.ReactNode | undefined;
  /** A message about *this* field. Marks the control invalid and describes it. */
  error?: string | undefined;
  /** Adds the show/hide control. Only meaningful with `type="password"`. */
  reveal?: boolean | undefined;
} & React.InputHTMLAttributes<HTMLInputElement>) {
  const id = `field-${name}`;
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;

  const [visible, setVisible] = React.useState(false);

  const type = reveal && visible ? 'text' : props.type;

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-baseline justify-between gap-3">
        <label htmlFor={id} className="text-label font-[510] text-fg">
          {label}
        </label>
        {hint ? (
          <span id={hintId} className="text-label-sm text-fg-faint">
            {hint}
          </span>
        ) : null}
        {hintNode}
      </div>

      <div className="relative">
        <input
          {...props}
          type={type}
          id={id}
          name={name}
          aria-invalid={error ? true : undefined}
          aria-describedby={[hintId, errorId].filter(Boolean).join(' ') || undefined}
          className={cn(
            'h-9 w-full rounded-md bg-surface-inset px-3 text-body text-fg',
            'ring-hairline outline-none placeholder:text-fg-faint',
            'transition-[background-color,box-shadow] duration-(--duration-fast)',
            'hover:bg-surface-hover',
            'focus-visible:ring-2 focus-visible:ring-accent',
            // The invalid ring replaces the hairline rather than sitting beside it, so an
            // errored field reads as one edge in one colour instead of two competing rings.
            error && 'ring-2 ring-danger',
            reveal && 'pr-10',
          )}
        />

        {reveal ? (
          <button
            type="button"
            onClick={() => setVisible((previous) => !previous)}
            aria-pressed={visible}
            aria-label={visible ? 'Hide password' : 'Show password'}
            className={cn(
              'absolute inset-y-0 right-0 flex w-10 items-center justify-center rounded-r-md',
              'text-fg-faint transition-colors duration-(--duration-fast) hover:text-fg',
            )}
          >
            {visible ? (
              <EyeOff aria-hidden className="size-4" />
            ) : (
              <Eye aria-hidden className="size-4" />
            )}
          </button>
        ) : null}
      </div>

      {error ? (
        <p id={errorId} className="text-label-sm text-danger">
          {error}
        </p>
      ) : null}
    </div>
  );
}
