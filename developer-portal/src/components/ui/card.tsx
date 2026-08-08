'use client';

import { motion, type HTMLMotionProps } from 'framer-motion';
import * as React from 'react';

import { cardVariants, hoverLift } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * The surface everything sits on (M23.1, redesigned to the Linear system).
 *
 * ── Depth is a hairline ───────────────────────────────────────────────────────────────
 *
 * "Elevation is expressed through subtle 1px inset borders and low-opacity drop shadows rather
 * than dramatic layering." So a card is a fill one step lighter than the canvas plus an inset
 * ring — `shadow-border-inset` from the extraction, verbatim — and no drop shadow at all in
 * the dark theme. `edge-light` adds the single-pixel top highlight that makes the panel read as
 * catching light from above without a gradient anywhere.
 *
 * An **inset** ring rather than a border because it takes no layout space, which is what lets a
 * card animate without its edge shifting a subpixel.
 *
 * ── 8px, not 16px ─────────────────────────────────────────────────────────────────────
 *
 * The token table maps `radius-2xl` to "Card corner", but the system's own summary says
 * "2–6px dominant" and the product screenshots show panels far tighter than 16px. 8px is the
 * reading that satisfies both — and once chosen it has to hold everywhere, because the
 * reference's other rule is "Don't mix rounded and sharp corners in the same view."
 */

type CardProps = HTMLMotionProps<'div'> & {
  /** Animate in on mount. Off by default so a card inside an animated list is not staggered twice. */
  animate?: boolean | undefined;
  /**
   * Lift on hover. For cards that are links or buttons; never for static panels.
   *
   * The travel comes from `hoverLift` rather than from a literal here, so every liftable
   * surface in the system moves by the same amount — and so the reasoning for 1px of `y`
   * instead of a scale lives in one place.
   */
  interactive?: boolean | undefined;
};

export function Card({ className, animate = false, interactive = false, ...props }: CardProps) {
  return (
    <motion.div
      className={cn(
        'edge-light relative rounded-lg bg-surface ring-hairline',
        /*
         * The colour transition is applied only to interactive cards, and that is a fix
         * rather than an optimisation. A static card has nothing to transition *to* — its
         * fill only ever changes when the theme does, and a theme switch should be
         * instantaneous. Worse, a transitioned background on an element Framer Motion has
         * composited was observed holding its old value across a theme change on the
         * landing page while an identical fresh element rendered correctly.
         */
        interactive &&
          'cursor-pointer transition-colors duration-(--duration-fast) ease-(--ease-out-quart) hover:bg-surface-hover',
        className,
      )}
      {...(animate ? { variants: cardVariants, initial: 'hidden', animate: 'visible' } : {})}
      {...(interactive ? hoverLift : {})}
      {...props}
    />
  );
}

export function CardHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex flex-col gap-1 px-5 pt-4 pb-3', className)} {...props} />;
}

export function CardTitle({ className, ...props }: React.HTMLAttributes<HTMLHeadingElement>) {
  return (
    <h3
      className={cn('text-title-2 font-[510] tracking-[-0.165px] text-fg', className)}
      {...props}
    />
  );
}

export function CardDescription({
  className,
  ...props
}: React.HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cn('text-body text-fg-subtle', className)} {...props} />;
}

export function CardContent({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('px-5 pb-5', className)} {...props} />;
}

export function CardFooter({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('flex items-center gap-2 border-t border-border-subtle px-5 py-3', className)}
      {...props}
    />
  );
}
