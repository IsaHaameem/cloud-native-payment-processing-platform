'use client';

import { Check, ChevronDown } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
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
import { type Mode } from '@/lib/session/session';

/**
 * The test/live switch (M23.2).
 *
 * ── Why this is a mutation and not a client-side toggle ───────────────────────────────
 *
 * `mode` is sealed into the session cookie, sent to the gateway as `X-PF-Mode`, validated there,
 * and bound into the signed internal context every downstream service trusts (M23.0, D184). It
 * selects which data plane the account reads. A value that consequential does not live in React
 * state — it lives on the server, and changing it is a guarded `POST` like any other mutation.
 *
 * That is also why it is guarded like any other mutation. A cross-site request that could flip a
 * merchant from test to live would put a human one click away from acting on real money while
 * believing they were in a sandbox — the precise confusion D184 exists to prevent.
 *
 * ── Two hidden forms, outside the menu ────────────────────────────────────────────────
 *
 * For the same reason as the account menu's sign-out, and with the same remedy: Radix unmounts
 * the menu content as part of selecting an item, so a submit button inside it never submits —
 * measured producing no request at all. The forms sit outside, and each item submits its own.
 *
 * ── Live mode is visually distinct, and that is a safety property ─────────────────────
 *
 * A merchant who believes they are in test mode while looking at live money is the failure this
 * badge exists to prevent. Test is the amber badge — visible, unmissable, and the state the
 * portal always starts in.
 */

const FORM_ID: Record<Mode, string> = {
  test: 'pf-mode-test-form',
  live: 'pf-mode-live-form',
};

export function ModeSwitch({ mode, csrfToken }: { mode: Mode; csrfToken: string }) {
  return (
    <>
      {(['test', 'live'] as const).map((value) => (
        <form key={value} id={FORM_ID[value]} action="/api/session/mode" method="post" hidden>
          <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
          <input type="hidden" name="mode" value={value} />
        </form>
      ))}

      <DropdownMenu>
        <DropdownMenuTrigger
          aria-label={`Data mode: ${mode}. Change`}
          className="flex items-center gap-1 rounded-md outline-none focus-visible:ring-2 focus-visible:ring-accent"
        >
          <Badge tone={mode === 'test' ? 'test' : 'outline'} dot>
            {mode === 'test' ? 'Test mode' : 'Live mode'}
          </Badge>
          <ChevronDown aria-hidden className="size-3 text-fg-faint" />
        </DropdownMenuTrigger>

        <DropdownMenuContent align="start" className="min-w-52">
          <DropdownMenuLabel>Data mode</DropdownMenuLabel>
          <DropdownMenuSeparator />
          <ModeOption value="test" label="Test mode" current={mode} />
          <ModeOption value="live" label="Live mode" current={mode} />
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
}

function ModeOption({ value, label, current }: { value: Mode; label: string; current: Mode }) {
  const selected = value === current;
  return (
    <DropdownMenuItem
      onSelect={() => submitById(FORM_ID[value])}
      className="justify-between"
      aria-current={selected}
    >
      <span>{label}</span>
      {selected ? <Check aria-hidden /> : null}
    </DropdownMenuItem>
  );
}
