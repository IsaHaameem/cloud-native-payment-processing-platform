'use client';

import { MotionConfig } from 'framer-motion';
import type * as React from 'react';

import { ToastProvider } from '@/components/ui/toast';
import { TooltipProvider } from '@/components/ui/tooltip';
import { QueryProvider } from '@/providers/query-provider';
import { ThemeProvider } from '@/providers/theme-provider';

/**
 * One client root, mounted once in the root layout (M23.1, redesigned; query cache added M23.3).
 *
 * Composed here rather than nested in the layout so adding a provider is a one-line change in one
 * file, and so the layout stays a description of the page rather than a stack of wrappers.
 *
 * `QueryProvider` sits **outside** `MotionConfig` and inside `ThemeProvider` for no reason other
 * than convention — none of the three observes the others. What matters is that it is here at
 * all: mounted once, so one cache serves the whole client tree, and mounted *per browser tab*
 * rather than per module, which is what keeps one merchant's cache out of another's render.
 * See `query-provider.tsx`.
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
      <QueryProvider>
        <MotionConfig reducedMotion="user">
          <TooltipProvider delayDuration={200}>
            <ToastProvider>{children}</ToastProvider>
          </TooltipProvider>
        </MotionConfig>
      </QueryProvider>
    </ThemeProvider>
  );
}
