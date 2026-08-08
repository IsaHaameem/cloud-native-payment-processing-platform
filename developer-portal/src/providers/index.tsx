'use client';

import { MotionConfig } from 'framer-motion';
import type * as React from 'react';

import { TooltipProvider } from '@/components/ui/tooltip';
import { ThemeProvider } from '@/providers/theme-provider';

/**
 * One client root, mounted once in the root layout (M23.1, redesigned).
 *
 * Composed here rather than nested in the layout so adding a provider — TanStack Query in
 * M23.3, the mode context after it — is a one-line change in one file, and so the layout stays
 * a description of the page rather than a stack of wrappers.
 *
 * `MotionConfig reducedMotion="user"` is the important line. It makes every Framer Motion
 * animation in the tree honour `prefers-reduced-motion` without a single component checking —
 * transforms are dropped and opacity is kept, which is exactly the right degradation: the state
 * change is still visible, the movement is not. The CSS half of the same rule lives in
 * globals.css, so both engines respect one preference.
 *
 * `delayDuration={200}` on tooltips: long enough that sweeping across the sidebar does not
 * flash eight of them, short enough that a deliberate hover feels immediate.
 */
export function Providers({ children }: { children: React.ReactNode }) {
  return (
    <ThemeProvider>
      <MotionConfig reducedMotion="user">
        <TooltipProvider delayDuration={200}>{children}</TooltipProvider>
      </MotionConfig>
    </ThemeProvider>
  );
}
