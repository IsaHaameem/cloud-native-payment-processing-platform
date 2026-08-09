'use client';

import {
  Activity,
  BarChart3,
  CreditCard,
  KeyRound,
  LayoutDashboard,
  Moon,
  Receipt,
  ScrollText,
  Search,
  Settings,
  Sun,
  Wallet,
  Webhook,
} from 'lucide-react';
import { useTheme } from 'next-themes';
import { useRouter } from 'next/navigation';
import * as React from 'react';

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
  const { setTheme } = useTheme();

  useCommandShortcut(React.useCallback(() => setOpen(true), []));

  const lookup = useObjectLookup(query);

  const items = React.useMemo<CommandItem[]>(() => {
    const go = (href: string) => () => router.push(href);
    return [
      {
        id: 'dashboard',
        label: 'Overview',
        group: 'Go to',
        icon: LayoutDashboard,
        keywords: ['dashboard', 'home', 'start'],
        onSelect: go('/dashboard'),
      },
      {
        id: 'foundation',
        label: 'Design foundation',
        group: 'Go to',
        icon: LayoutDashboard,
        keywords: ['design', 'system', 'components'],
        onSelect: go('/foundation'),
      },
      {
        id: 'home',
        label: 'Home',
        group: 'Go to',
        icon: Search,
        keywords: ['landing', 'start'],
        onSelect: go('/'),
      },

      /*
       * These are the destinations M23.4–M24 build. They are listed with the milestone in the
       * label rather than hidden, because the palette is also how someone discovers what the
       * product will be — but they navigate nowhere yet, so they are grouped apart and say so.
       */
      {
        id: 'dashboard',
        label: 'Overview — M23.8',
        group: 'Coming soon',
        icon: LayoutDashboard,
        onSelect: () => {},
      },
      {
        id: 'payments',
        label: 'Payments — M23.6',
        group: 'Coming soon',
        icon: CreditCard,
        onSelect: () => {},
      },
      {
        id: 'refunds',
        label: 'Refunds — M23.7',
        group: 'Coming soon',
        icon: Receipt,
        onSelect: () => {},
      },
      {
        id: 'keys',
        label: 'API keys — M23.5',
        group: 'Coming soon',
        icon: KeyRound,
        onSelect: () => {},
      },
      {
        id: 'balance',
        label: 'Balance — M24',
        group: 'Coming soon',
        icon: Wallet,
        onSelect: () => {},
      },
      {
        id: 'webhooks',
        label: 'Webhooks — M24',
        group: 'Coming soon',
        icon: Webhook,
        onSelect: () => {},
      },
      {
        id: 'logs',
        label: 'Request logs — M24',
        group: 'Coming soon',
        icon: ScrollText,
        onSelect: () => {},
      },
      {
        id: 'events',
        label: 'Events — M24',
        group: 'Coming soon',
        icon: Activity,
        onSelect: () => {},
      },
      {
        id: 'analytics',
        label: 'Analytics — M24',
        group: 'Coming soon',
        icon: BarChart3,
        onSelect: () => {},
      },
      {
        id: 'settings',
        label: 'Settings — M23.4',
        group: 'Coming soon',
        icon: Settings,
        onSelect: () => {},
      },

      {
        id: 'theme-dark',
        label: 'Switch to dark',
        group: 'Theme',
        icon: Moon,
        keywords: ['appearance', 'night'],
        onSelect: () => setTheme('dark'),
      },
      {
        id: 'theme-light',
        label: 'Switch to light',
        group: 'Theme',
        icon: Sun,
        keywords: ['appearance', 'day'],
        onSelect: () => setTheme('light'),
      },
    ];
  }, [router, setTheme]);

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
