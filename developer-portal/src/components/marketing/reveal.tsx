'use client';

import { motion } from 'framer-motion';

import { listItemVariants, listVariants } from '@/lib/motion';

/**
 * A block that reveals itself when it is scrolled to (M23.2a).
 *
 * ── Once, and only slightly ───────────────────────────────────────────────────────────
 *
 * `viewport={{ once: true }}` — an element that re-animates every time it re-enters the viewport
 * turns scrolling back up into a performance, and on a long page it is the single most tiring
 * effect there is.
 *
 * The movement is the system's own `listItemVariants`: 6px and 220ms, the same as a table row
 * entering inside the product. A marketing page that reveals with 40px of travel and a half
 * second of easing is a different design language from the product it is selling, and the
 * seam is visible the moment a visitor signs in.
 *
 * `amount: 0.15` rather than the default `some`: a tall section should start appearing when its
 * top edge is comfortably in view, not when a single pixel of it is.
 *
 * ── Reduced motion is handled centrally ───────────────────────────────────────────────
 *
 * `MotionConfig reducedMotion="user"` in the providers drops the `y` and keeps the opacity, so
 * nothing here checks the preference and nothing here can forget to.
 */
export function Reveal({
  children,
  className,
  stagger = false,
}: {
  children: React.ReactNode;
  className?: string | undefined;
  /** Reveal direct children in sequence rather than the block as one unit. */
  stagger?: boolean | undefined;
}) {
  return (
    <motion.div
      variants={stagger ? listVariants : listItemVariants}
      initial="hidden"
      whileInView="visible"
      viewport={{ once: true, amount: 0.15 }}
      {...(className ? { className } : {})}
    >
      {children}
    </motion.div>
  );
}

/** One child of a staggered {@link Reveal}. */
export function RevealItem({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string | undefined;
}) {
  return (
    <motion.div variants={listItemVariants} {...(className ? { className } : {})}>
      {children}
    </motion.div>
  );
}
