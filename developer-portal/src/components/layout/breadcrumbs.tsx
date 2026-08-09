'use client';

import { ChevronRight } from 'lucide-react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import * as React from 'react';

import { NAV_SECTIONS } from '@/components/layout/nav-items';
import { truncateId } from '@/lib/format';
import { cn } from '@/lib/utils';

/**
 * Where you are, in the header (M23.3).
 *
 * `app-header.tsx` reserved this slot in M23.1 with a note that it would be filled "once there is
 * a route tree deep enough to need them". M23.6 and M23.7 add `/payments/[id]` and
 * `/refunds/[id]`, so the tree is about to be deep enough, and the component that names the
 * trail has to exist before the screens that rely on it.
 *
 * ── The labels come from the navigation, not from the URL ─────────────────────────────
 *
 * `/developers/api-keys` should read "API keys", not "Api Keys" — and the correct spelling
 * already exists in `NAV_SECTIONS`, which is the single source for the information architecture
 * (D193). Title-casing path segments would invent a second, worse spelling of every label and
 * guarantee the two drift.
 *
 * A segment with no nav entry is either a section prefix (`/developers`) or an object id. Section
 * prefixes are rendered as plain text rather than links, because `/developers` is not a page.
 * Ids are truncated the way every id in the portal is truncated, so a 36-character UUID does not
 * push the mode switch off a narrow header.
 *
 * ── Hidden at the root, and below `sm` ────────────────────────────────────────────────
 *
 * On `/dashboard` the trail would be one item repeating the page title — noise. On a phone the
 * header has room for the mode switch and the account menu and nothing else, and the page's own
 * `<h1>` already says where you are.
 */

interface Crumb {
  readonly label: string;
  readonly href: string | undefined;
}

/** Every enabled destination, flattened once, so lookup is a map read rather than a scan. */
const NAV_LABELS: ReadonlyMap<string, string> = new Map(
  NAV_SECTIONS.flatMap((section) => section.items.map((item) => [item.href, item.label] as const)),
);

/**
 * Section prefixes that group routes without being routes themselves.
 *
 * Derived from the navigation rather than listed: any path that is a strict prefix of a nav
 * destination but is not itself one is a grouping segment.
 */
const SECTION_LABELS: ReadonlyMap<string, string> = new Map(
  [...NAV_LABELS.keys()]
    .map((href) => href.split('/').slice(0, 2).join('/'))
    .filter((prefix) => prefix.length > 1 && !NAV_LABELS.has(prefix))
    .map((prefix) => [prefix, prefix.slice(1).replace(/^./, (c) => c.toUpperCase())] as const),
);

export function buildCrumbs(pathname: string): readonly Crumb[] {
  const segments = pathname.split('/').filter((segment) => segment.length > 0);

  return segments.map((segment, index) => {
    const href = `/${segments.slice(0, index + 1).join('/')}`;

    const navLabel = NAV_LABELS.get(href);
    if (navLabel !== undefined) return { label: navLabel, href };

    const sectionLabel = SECTION_LABELS.get(href);
    if (sectionLabel !== undefined) return { label: sectionLabel, href: undefined };

    // Anything left is an object id. It is the current page, so it gets no link.
    return { label: truncateId(segment), href: undefined };
  });
}

export function Breadcrumbs({ className }: { className?: string | undefined }) {
  const pathname = usePathname();
  const crumbs = React.useMemo(() => buildCrumbs(pathname), [pathname]);

  if (crumbs.length < 2) return null;

  return (
    <nav aria-label="Breadcrumb" className={cn('hidden min-w-0 items-center sm:flex', className)}>
      <ol className="flex min-w-0 items-center gap-1">
        {crumbs.map((crumb, index) => {
          const last = index === crumbs.length - 1;
          return (
            <li key={`${crumb.label}-${index}`} className="flex min-w-0 items-center gap-1">
              {index > 0 ? (
                <ChevronRight aria-hidden className="size-3 shrink-0 text-fg-faint" />
              ) : null}

              {crumb.href && !last ? (
                <Link
                  href={crumb.href}
                  className="truncate rounded-sm text-label text-fg-subtle transition-colors duration-(--duration-fast) hover:text-fg"
                >
                  {crumb.label}
                </Link>
              ) : (
                <span
                  // The last crumb is the current page. `aria-current` is what tells a screen
                  // reader that, since it is not a link and has nothing else to distinguish it.
                  {...(last ? { 'aria-current': 'page' as const } : {})}
                  className={cn(
                    'truncate text-label',
                    last ? 'font-[510] text-fg' : 'text-fg-subtle',
                  )}
                >
                  {crumb.label}
                </span>
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
