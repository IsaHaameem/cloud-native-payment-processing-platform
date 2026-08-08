'use client';

import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import { motion, type HTMLMotionProps } from 'framer-motion';
import * as React from 'react';

import { duration, ease, pressScale } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * The button (M23.1, redesigned to the Linear system).
 *
 * ── What the reference dictates ───────────────────────────────────────────────────────
 *
 * - **6px radius.** "The radius language is deliberately small (2–6px dominant)." The pill
 *   shape the reference's landing page uses on `Sign Up` is reserved for that one marketing
 *   CTA, not for product buttons.
 * - **13px / weight 510** with -0.13px tracking — the extracted `label-medium` role, which the
 *   system names for exactly this: "Sidebar nav labels, button labels, tags".
 * - **Indigo on one button per screen.** "Do use the primary color only for the single most
 *   important action per screen." That is a rule about usage, so a component cannot enforce it
 *   — but `primary` being visually loud and `secondary` being genuinely quiet is what makes
 *   obeying it the path of least resistance.
 * - **Depth is a hairline, not a shadow**: `secondary` is a surface with an inset ring.
 *
 * Five variants and no more. Every extra variant is a decision a future screen makes by
 * accident; these map to intents the product already has.
 *
 * The press response is a 3% scale. Buttons are small enough that scaling them does not
 * visibly resample their label — which is exactly why cards get a 1px lift instead.
 */
const buttonVariants = cva(
  [
    'inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-md',
    'text-label font-[510] select-none',
    'transition-[background-color,box-shadow,color,opacity]',
    'duration-(--duration-fast) ease-(--ease-out-quart)',
    'disabled:pointer-events-none disabled:opacity-40',
    "[&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
  ],
  {
    variants: {
      variant: {
        primary: 'bg-accent text-fg-on-accent hover:bg-accent-hover',
        secondary: 'bg-surface-elevated text-fg ring-hairline hover:bg-surface-active',
        ghost: 'text-fg-subtle hover:bg-surface-hover hover:text-fg',
        danger: 'bg-danger-solid text-fg-on-accent hover:opacity-90',
        link: 'text-accent-text underline-offset-4 hover:underline',
      },
      size: {
        sm: 'h-7 px-2',
        md: 'h-8 px-3',
        lg: 'h-9 px-3.5',
        icon: 'size-7',
      },
    },
    defaultVariants: { variant: 'secondary', size: 'md' },
  },
);

/*
 * The public props stay **React's**, not Framer Motion's.
 *
 * `HTMLMotionProps` redeclares `style`, `onAnimationStart` and the drag handlers with
 * incompatible shapes, and under `exactOptionalPropertyTypes` (D194) the two sets do not unify.
 * Typing this interface from motion would fix the internals and push the problem onto every
 * caller: `<Button onAnimationStart={…}>` would silently mean something else, and `style` would
 * stop accepting a plain `CSSProperties`.
 *
 * So the mismatch is absorbed here, at the single place it exists, by a narrow cast on the
 * props handed to `motion.button`. The keys that genuinely differ are the animation ones, and
 * this component never forwards those — it owns `whileTap` and `transition` itself.
 */
export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof buttonVariants> {
  asChild?: boolean | undefined;
}

export function Button({ className, variant, size, asChild = false, ...props }: ButtonProps) {
  // `asChild` hands the styles to the caller's element — a `<Link>` that should look like a
  // button. Motion is dropped in that case rather than wrapped: animating someone else's
  // element from in here would fight whatever they already do with it.
  if (asChild) {
    return <Slot className={cn(buttonVariants({ variant, size }), className)} {...props} />;
  }

  return (
    <motion.button
      className={cn(buttonVariants({ variant, size }), className)}
      // `pressScale` rather than a literal, so the press response is one decision rather than
      // one per component. See `motion.ts` for why a button may scale where a card may not.
      {...pressScale}
      transition={{ duration: duration.instant, ease: ease.standard }}
      /*
       * Through `unknown` because the two prop sets genuinely do not overlap: React's
       * `onAnimationStart` takes an `AnimationEvent`, Motion's takes an `AnimationDefinition`,
       * and TypeScript is right that neither is assignable to the other. Nothing here forwards
       * an animation handler — this component owns `whileTap` and `transition` — so the
       * conflict is real in the types and absent in the values.
       */
      {...(props as unknown as HTMLMotionProps<'button'>)}
    />
  );
}

export { buttonVariants };
