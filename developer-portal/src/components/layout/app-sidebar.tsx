'use client';

import { motion } from 'framer-motion';
import { PanelLeftClose, PanelLeftOpen } from 'lucide-react';
import * as React from 'react';

import { LogoMark, Wordmark } from '@/components/layout/logo';
import { SidebarNav } from '@/components/layout/sidebar-nav';
import { Button } from '@/components/ui/button';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { spring } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * The desktop rail (M23.1, redesigned to the Linear system).
 *
 * `background-elevated` (#23252a) against the `background-primary` canvas — the exact role
 * pairing the reference names, and the reason the rail reads as a distinct plane without a
 * border doing the work. Fixed at 240px, collapsible to 52px, hidden below `lg`.
 *
 * ── The width is a spring, not a transition ───────────────────────────────────────────
 *
 * Animating `width` in CSS forces layout on every frame for the whole page — the rail is a
 * flex sibling of the entire content column, so every frame reflows the document. Framer
 * Motion's spring drives the same property but through its own animation loop with a single
 * committed style per frame, and the content column is told to animate its own margin in step.
 * The result is a rail that feels physical rather than timed, which is what makes the collapse
 * read as a real object moving.
 *
 * ── Why the collapsed state is a cookie ───────────────────────────────────────────────
 *
 * So the *server* can read it and render the correct width in the first HTML it sends.
 * `localStorage` is only readable after hydration, which means the rail renders wide and then
 * snaps narrow — a layout shift on every navigation, on the element that frames the page.
 */
const COOKIE = 'pf_sidebar_collapsed';
const ONE_YEAR = 60 * 60 * 24 * 365;

const EXPANDED = 240;
const COLLAPSED = 52;

export function AppSidebar({
  defaultCollapsed = false,
}: {
  defaultCollapsed?: boolean | undefined;
}) {
  const [collapsed, setCollapsed] = React.useState(defaultCollapsed);

  const toggle = React.useCallback(() => {
    setCollapsed((previous) => {
      const next = !previous;
      document.cookie = `${COOKIE}=${next}; path=/; max-age=${ONE_YEAR}; samesite=lax`;
      return next;
    });
  }, []);

  return (
    <motion.aside
      data-collapsed={collapsed}
      className={cn(
        'sticky top-0 hidden h-dvh shrink-0 flex-col overflow-hidden bg-surface-elevated lg:flex',
      )}
      initial={false}
      animate={{ width: collapsed ? COLLAPSED : EXPANDED }}
      transition={spring}
    >
      <div
        className={cn(
          // Matches the sticky header height so the wordmark and the breadcrumb sit on one line.
          'flex h-14 shrink-0 items-center',
          collapsed ? 'justify-center px-0' : 'px-4',
        )}
      >
        {collapsed ? <LogoMark /> : <Wordmark />}
      </div>

      <SidebarNav collapsed={collapsed} />

      <div className={cn('p-2', collapsed && 'flex justify-center')}>
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              variant="ghost"
              size={collapsed ? 'icon' : 'md'}
              onClick={toggle}
              aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
              aria-pressed={collapsed}
              className={cn(!collapsed && 'w-full justify-start')}
            >
              {collapsed ? <PanelLeftOpen /> : <PanelLeftClose />}
              {!collapsed ? <span>Collapse</span> : null}
            </Button>
          </TooltipTrigger>
          <TooltipContent side="right">{collapsed ? 'Expand' : 'Collapse'} sidebar</TooltipContent>
        </Tooltip>
      </div>
    </motion.aside>
  );
}

export { COOKIE as SIDEBAR_COLLAPSED_COOKIE };
