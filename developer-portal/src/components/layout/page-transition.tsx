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
 */
export function PageTransition({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();

  return (
    <AnimatePresence mode="popLayout" initial={false}>
      <motion.div
        key={pathname}
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
