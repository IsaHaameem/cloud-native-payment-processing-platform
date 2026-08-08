'use client';

import { motion } from 'framer-motion';
import * as React from 'react';

import { cardVariants, duration, ease } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * What a surface shows when it has nothing (M23.1 redesign).
 *
 * A component rather than a convention because the rule it carries is easy to state and easy to
 * skip: every list gets a designed empty state that teaches the next action. Three slots — what
 * this is, what to do, and one button that does it — so writing one is easier than rendering
 * "No results".
 *
 * ── The glow, and the one ring that breathes ──────────────────────────────────────────
 *
 * A soft radial wash behind the icon plate, and a single ring that expands and fades once on
 * mount. This is the only ambient motion in the portal, and it earns its place here because an
 * empty state is the one surface with nothing else to look at — everywhere else, motion competes
 * with content. It runs **once**, not on a loop: a pulsing empty state is a screen that will not
 * stop asking for attention it does not deserve.
 *
 * `prefers-reduced-motion` removes it centrally through `MotionConfig` in the providers, and
 * what remains — plate, title, description, button — still reads exactly as intended.
 *
 * ── The distinction callers must make ─────────────────────────────────────────────────
 *
 * *No data yet* and *no results for these filters* feel identical to build and are entirely
 * different to read: one wants a quickstart, the other wants the filters cleared. Screens are
 * expected to pass different copy and a different action for each.
 */
export function EmptyState({
  icon,
  title,
  description,
  action,
  className,
}: {
  icon?: React.ReactNode | undefined;
  title: string;
  description?: string | undefined;
  action?: React.ReactNode | undefined;
  className?: string | undefined;
}) {
  return (
    <motion.div
      variants={cardVariants}
      initial="hidden"
      animate="visible"
      className={cn(
        'gpu relative flex flex-col items-center justify-center overflow-hidden',
        'rounded-lg bg-surface px-6 py-16 text-center ring-hairline',
        className,
      )}
    >
      <div
        aria-hidden
        className="pointer-events-none absolute inset-x-0 top-0 h-44 bg-[radial-gradient(ellipse_45%_60%_at_50%_0%,var(--color-accent-subtle),transparent_70%)]"
      />

      {icon ? (
        <div className="relative mb-5 flex size-10 items-center justify-center">
          {/* One expanding ring, once. Purely decorative, so it never carries meaning. */}
          <motion.span
            aria-hidden
            className="absolute inset-0 rounded-lg ring-1 ring-accent-ring"
            initial={{ opacity: 0.5, scale: 1 }}
            animate={{ opacity: 0, scale: 1.9 }}
            transition={{ duration: 1.1, ease: ease.outQuart, delay: 0.15 }}
          />
          <motion.div
            aria-hidden
            initial={{ scale: 0.9, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            transition={{ duration: duration.base, ease: ease.outQuart }}
            className="relative flex size-10 items-center justify-center rounded-lg bg-surface-elevated text-fg-subtle ring-hairline"
          >
            {icon}
          </motion.div>
        </div>
      ) : null}

      <p className="relative text-body-lg font-[510] text-fg">{title}</p>
      {description ? (
        <p className="relative mt-1.5 max-w-sm text-body text-balance text-fg-subtle">
          {description}
        </p>
      ) : null}
      {action ? <div className="relative mt-6">{action}</div> : null}
    </motion.div>
  );
}
