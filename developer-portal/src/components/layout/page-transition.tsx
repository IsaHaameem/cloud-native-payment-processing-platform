'use client';

import { AnimatePresence, motion } from 'framer-motion';
import { usePathname } from 'next/navigation';
import type * as React from 'react';

import { pageVariants } from '@/lib/motion';

/**
 * Page transitions (M23.1 redesign).
 *
 * ── What it does, and what it deliberately does not ───────────────────────────────────
 *
 * `mode="wait"` would run the old page's exit *before* the new page enters, which doubles the
 * perceived latency of every navigation — the user waits 140ms to see something they already
 * asked for. `mode="popLayout"` lets them cross-fade in place instead, so a navigation costs
 * one 220ms fade and nothing more.
 *
 * The travel is 4px. A page that slides further reads as a whole context being replaced, which
 * is wrong for a dashboard where the shell stays put and only the panel changes.
 *
 * The `key` is the pathname, so a route change animates and a re-render does not. Query-string
 * changes — a filter, a cursor page — deliberately do *not* re-key: re-animating a table
 * because someone typed in a search box is the fastest way to make motion feel like latency.
 *
 * ── `data-pathname` is that key, written where the DOM can see it ─────────────────────
 *
 * `popLayout` means both pages are mounted at once for the length of the fade, so a bare
 * `main h1` matches whichever happens to come first in document order — the page being left or
 * the page arriving, depending on how the commit interleaved. `route-focus.tsx` has to tell them
 * apart to move focus to the incoming heading, and React's `key` is invisible from the DOM. This
 * attribute is that key, published: it says which route a subtree belongs to, so focus management
 * can select the incoming page positively instead of inferring it.
 */
export function PageTransition({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();

  return (
    <AnimatePresence mode="popLayout" initial={false}>
      <motion.div
        key={pathname}
        data-pathname={pathname}
        className="gpu"
        variants={pageVariants}
        initial="hidden"
        animate="visible"
        exit="exit"
      >
        {children}
      </motion.div>
    </AnimatePresence>
  );
}
