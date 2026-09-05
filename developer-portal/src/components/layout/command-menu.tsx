'use client';

import { Activity, CreditCard, Home, Receipt, Search } from 'lucide-react';
import { useRouter } from 'next/navigation';
import * as React from 'react';

import { NAV_SECTIONS } from '@/components/layout/nav-items';
import {
  CommandPalette,
  useCommandShortcut,
  type CommandItem,
} from '@/components/ui/command-palette';
import { Button } from '@/components/ui/button';
import { formatMoney, truncateId } from '@/lib/format';
import { useObjectLookup } from '@/lib/query/object-lookup';
import { cn } from '@/lib/utils';

/**
 * The portal's ⌘K, wired (M23.1 redesign).
 *
 * ── What it can do today, and what it will ────────────────────────────────────────────
 *
 * Navigation and theme, because those are the only actions that exist. M23.3 adds object lookup
 * by id — paste `pay_…` and go — and M24 adds cross-service search. The list is data, so each of
 * those is an entry rather than a rewrite.
 *
 * Destinations later milestones build are **absent**, not disabled. That is the opposite choice
 * from the sidebar, and deliberately: a sidebar is a map of the product and should show its whole
 * shape, while a palette is a list of things you can do right now. An unavailable row in a
 * palette is a row the user has to read and reject on every search.
 *
 * ── The trigger is a button, not a hint ───────────────────────────────────────────────
 *
 * A ⌘K shortcut nobody has been told about is a feature for the person who wrote it. The header
 * carries a real, clickable control that shows the shortcut — which is also the only way in on a
 * touch device, where there is no keyboard to press.
 */
/** Icons per object kind, so the pinned row reads as the thing it found. */
const OBJECT_ICON = {
  payment: CreditCard,
  refund: Receipt,
  event: Activity,
} as const;

/**
 * One line describing a resolved object.
 *
 * Reads the platform's own fields defensively — three different response shapes come through this
 * path and only `id` is common to all of them — so a missing field degrades the label rather than
 * throwing inside a `useMemo`.
 */
function describeObject(result: {
  kind: 'payment' | 'refund' | 'event';
  id: string;
  data: Record<string, unknown>;
}): string {
  const short = truncateId(result.id);

  if (result.kind === 'event') {
    const type = typeof result.data.type === 'string' ? result.data.type : 'event';
    return `Event ${short} · ${type}`;
  }

  const status = typeof result.data.status === 'string' ? result.data.status : 'unknown';
  const amount =
    typeof result.data.amountMinor === 'number' && typeof result.data.currency === 'string'
      ? ` · ${formatMoney(result.data.amountMinor, result.data.currency)}`
      : '';

  const noun = result.kind === 'payment' ? 'Payment' : 'Refund';
  return `${noun} ${short} · ${status}${amount}`;
}

export function CommandMenu() {
  const [open, setOpen] = React.useState(false);
  const [query, setQuery] = React.useState('');
  const router = useRouter();

  useCommandShortcut(React.useCallback(() => setOpen(true), []));

  const lookup = useObjectLookup(query);

  const items = React.useMemo<CommandItem[]>(() => {
    const go = (href: string) => () => router.push(href);

    // Every enabled destination in the sidebar IA, so "jump to" and the sidebar can never
    // disagree about what the product contains. Section-prefixed so a duplicate label
    // ("Overview" appears three times) still reads unambiguously.
    const destinations: CommandItem[] = NAV_SECTIONS.flatMap((section) =>
      section.items
        .filter((item) => item.enabled)
        .map((item) => ({
          id: `nav:${item.href}`,
          label: section.label ? `${section.label} · ${item.label}` : item.label,
          group: 'Go to',
          icon: item.icon,
          keywords: [item.label.toLowerCase(), section.label.toLowerCase()],
          onSelect: go(item.href),
        })),
    );

    return [
      {
        id: 'home',
        label: 'Marketing home',
        group: 'Go to',
        icon: Home,
        keywords: ['landing', 'public', 'site'],
        onSelect: go('/'),
      },
      ...destinations,
    ];
  }, [router]);

  /**
   * The resolved object, as a row the palette pins above its static results.
   *
   * Three states are worth showing and a fourth deliberately is not: resolving, found, and
   * genuinely-not-found each produce a row; an input that is not an id produces nothing at all,
   * because the palette is mostly used for navigation and a "no such object" under every partial
   * word would be noise.
   */
  const pinnedItems = React.useMemo<readonly CommandItem[]>(() => {
    if (lookup.candidates.length === 0) return [];

    if (lookup.isLoading) {
      return [
        {
          id: 'lookup-pending',
          label: `Looking up ${truncateId(query.trim())}…`,
          group: 'Object',
          icon: Search,
          onSelect: () => {},
        },
      ];
    }

    if (lookup.error) {
      return [
        {
          id: 'lookup-error',
          label: lookup.error.message,
          group: 'Object',
          icon: Search,
          onSelect: () => {},
        },
      ];
    }

    if (lookup.result) {
      return [
        {
          id: 'lookup-result',
          label: describeObject(lookup.result),
          group: 'Object',
          icon: OBJECT_ICON[lookup.result.kind],
          // The detail screens are M23.6/M23.7. Until they exist the row identifies the object,
          // which is the half of "paste an id and go" that the data layer owns; the other half is
          // one `router.push` added by the milestone that builds the destination.
          onSelect: () => {},
        },
      ];
    }

    return [
      {
        id: 'lookup-missing',
        label: `No payment, refund or event with id ${truncateId(query.trim())}`,
        group: 'Object',
        icon: Search,
        onSelect: () => {},
      },
    ];
  }, [lookup, query]);

  return (
    <>
      <Button
        variant="ghost"
        size="sm"
        onClick={() => setOpen(true)}
        className={cn(
          'group hidden gap-2 pr-1.5 pl-2 text-fg-subtle hover:text-fg sm:inline-flex',
          'w-56 justify-start',
        )}
      >
        <Search className="size-3.5" />
        <span className="flex-1 text-left font-[400]">Search…</span>
        <kbd className="rounded-sm bg-surface-active px-1.5 py-0.5 text-caption font-[510] text-fg-subtle">
          ⌘K
        </kbd>
      </Button>

      {/* The same entry point where there is no room for the wide control, and no keyboard. */}
      <Button
        variant="ghost"
        size="icon"
        aria-label="Search"
        onClick={() => setOpen(true)}
        className="sm:hidden"
      >
        <Search />
      </Button>

      <CommandPalette
        open={open}
        onOpenChange={setOpen}
        items={items}
        pinnedItems={pinnedItems}
        onQueryChange={setQuery}
        placeholder="Search, or paste a payment, refund or event id…"
      />
    </>
  );
}
