'use client';

import { motion } from 'framer-motion';
import Link from 'next/link';
import { usePathname } from 'next/navigation';

import { NAV_SECTIONS, type NavItem } from '@/components/layout/nav-items';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { duration, ease } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * The navigation list (M23.1, redesigned to the Linear system), shared by the fixed desktop
 * rail and the mobile drawer. One component for both is what keeps the two from drifting — the
 * drawer is not a second navigation, it is the same one in a different container.
 *
 * ── What the reference dictates ───────────────────────────────────────────────────────
 *
 * Nav items are the extracted `nav-item` role: **13px, weight 400, -0.13px tracking**. Section
 * headings are `caption` — 10px, weight 510, uppercase. Rows are tight (28px) because the
 * system is explicitly "high-density"; the density is the point, not a compromise.
 *
 * ── The active indicator is a shared layout animation ─────────────────────────────────
 *
 * `layoutId` makes the 2px accent bar *travel* between items as the route changes, rather than
 * disappearing here and reappearing there. It is the single most characteristic motion in this
 * class of product, it costs one prop, and Framer Motion drives it entirely on the compositor.
 */
export function SidebarNav({
  collapsed = false,
  onNavigate,
}: {
  collapsed?: boolean | undefined;
  onNavigate?: (() => void) | undefined;
}) {
  const pathname = usePathname();

  return (
    <nav aria-label="Main" className="flex flex-1 flex-col gap-6 overflow-y-auto px-3 py-4">
      {NAV_SECTIONS.map((section) => (
        <div key={section.label} className="flex flex-col gap-0.5">
          {!collapsed ? (
            <p className="px-2 pb-1 text-caption font-[510] tracking-[0.04em] text-fg-subtle uppercase">
              {section.label}
            </p>
          ) : (
            // The rail still needs the grouping, but a truncated word is noise. A hairline
            // says "these belong together" without pretending to be a label.
            <div aria-hidden className="mx-2 mb-1 h-px bg-border-subtle" />
          )}

          <ul className="flex flex-col gap-px">
            {section.items.map((item) => (
              <li key={item.href}>
                <NavEntry
                  item={item}
                  pathname={pathname}
                  collapsed={collapsed}
                  onNavigate={onNavigate}
                />
              </li>
            ))}
          </ul>
        </div>
      ))}
    </nav>
  );
}

function NavEntry({
  item,
  pathname,
  collapsed,
  onNavigate,
}: {
  item: NavItem;
  pathname: string;
  collapsed: boolean;
  onNavigate?: (() => void) | undefined;
}) {
  // Exact match, or a real path segment beneath it — so `/payments` lights up for
  // `/payments/pay_123` but `/paymentsomething` does not.
  const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
  const Icon = item.icon;

  const shared = cn(
    'group relative flex h-7 items-center gap-2 rounded-md px-2',
    'text-label font-[400]',
    'transition-colors duration-(--duration-fast) ease-(--ease-out-quart)',
    collapsed && 'justify-center px-0',
  );

  const body = (
    <>
      <Icon
        className={cn(
          'size-4 shrink-0 transition-colors duration-(--duration-fast)',
          active ? 'text-fg' : 'text-fg-faint group-hover:text-fg-subtle',
        )}
      />
      {!collapsed ? <span className="truncate">{item.label}</span> : null}
    </>
  );

  if (!item.enabled) {
    // Rendered, not hidden, and genuinely inert: `aria-disabled` plus no href means a screen
    // reader announces it as unavailable rather than reading out a link that goes nowhere.
    return (
      <Tooltip>
        <TooltipTrigger asChild>
          <span aria-disabled="true" className={cn(shared, 'cursor-not-allowed text-fg-faint')}>
            {body}
          </span>
        </TooltipTrigger>
        <TooltipContent side="right">
          {item.label} — arrives in {item.milestone}
        </TooltipContent>
      </Tooltip>
    );
  }

  const link = (
    <Link
      href={item.href}
      aria-current={active ? 'page' : undefined}
      {...(onNavigate ? { onClick: onNavigate } : {})}
      className={cn(
        shared,
        active ? 'bg-surface-active text-fg' : 'text-fg-muted hover:bg-surface-hover hover:text-fg',
      )}
    >
      {active ? (
        <motion.span
          aria-hidden
          layoutId="nav-active-indicator"
          className="absolute inset-y-1 left-0 w-0.5 rounded-full bg-accent"
          transition={{ duration: duration.base, ease: ease.outQuart }}
        />
      ) : null}
      {body}
    </Link>
  );

  if (!collapsed) return link;

  // Collapsed, the icon is the only thing left — so the label has to come from somewhere, and
  // a tooltip that opens on focus as well as hover is that somewhere.
  return (
    <Tooltip>
      <TooltipTrigger asChild>{link}</TooltipTrigger>
      <TooltipContent side="right">{item.label}</TooltipContent>
    </Tooltip>
  );
}
