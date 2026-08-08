'use client';

import { motion, useInView, useMotionValue, useSpring, useTransform } from 'framer-motion';
import { ArrowDownRight, ArrowUpRight, Minus } from 'lucide-react';
import * as React from 'react';

import { Sparkline } from '@/components/patterns/sparkline';
import { cardVariants, countTransition } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * A metric card (M23.1 redesign) — the tile an overview is built from.
 *
 * ── The number counts, and that is the one slow animation in the system ───────────────
 *
 * Everything else here runs in 140–220ms. The count runs in 600ms, because a figure that snaps
 * into place is read as a static label while one that counts is read as *measured*. It fires
 * once, when the card first scrolls into view, and never on a re-render — so it can never delay
 * a value the user is waiting on, and switching a filter does not re-run the theatre.
 *
 * `useInView(once)` rather than on mount: a tile below the fold that has already finished
 * counting by the time it is scrolled to has animated for nobody.
 *
 * ── The delta is never colour alone ───────────────────────────────────────────────────
 *
 * Up, down and flat each get an arrow as well as a tone, and the sign is in the text. A
 * red-versus-green pair carrying the whole meaning is the most common accessibility failure in
 * dashboards, and it costs one icon to avoid.
 *
 * ── Direction is not sentiment ────────────────────────────────────────────────────────
 *
 * `invertDelta` exists because "up" is good for volume and bad for failure rate. The caller
 * knows which; the component must not guess, and a component that guessed would eventually
 * colour a rising decline rate green.
 */
export function MetricCard({
  label,
  value,
  format,
  delta,
  invertDelta = false,
  series,
  hint,
  className,
}: {
  label: string;
  /** The final numeric value. Counting needs a number, so formatting is a separate concern. */
  value: number;
  /** Renders the number at each frame — `formatMoney`, a percentage, a plain count. */
  format: (value: number) => string;
  /** Fractional change, e.g. `0.124` for +12.4%. Omit when there is nothing to compare against. */
  delta?: number | undefined;
  invertDelta?: boolean | undefined;
  series?: readonly number[] | undefined;
  hint?: string | undefined;
  className?: string | undefined;
}) {
  const ref = React.useRef<HTMLDivElement>(null);
  const inView = useInView(ref, { once: true, margin: '-40px' });

  const motionValue = useMotionValue(0);
  const spring = useSpring(motionValue, { duration: countTransition.duration! * 1000, bounce: 0 });
  const text = useTransform(spring, (latest) => format(latest));

  React.useEffect(() => {
    if (inView) motionValue.set(value);
  }, [inView, value, motionValue]);

  const direction = delta === undefined ? 'flat' : delta > 0 ? 'up' : delta < 0 ? 'down' : 'flat';
  const good = invertDelta ? direction === 'down' : direction === 'up';
  const DeltaIcon =
    direction === 'up' ? ArrowUpRight : direction === 'down' ? ArrowDownRight : Minus;

  return (
    <motion.div
      ref={ref}
      variants={cardVariants}
      className={cn(
        'edge-light group relative overflow-hidden rounded-lg bg-surface p-4 ring-hairline',
        className,
      )}
    >
      {/*
       * A glow that only appears on hover, anchored to the top-left where the label is. It is
       * the whole of the "elevation" this card gets — no shadow, no scale, nothing that would
       * resample the hairline ring or the tabular figures.
       */}
      <div
        aria-hidden
        className={cn(
          'pointer-events-none absolute -top-16 -left-10 h-40 w-56 rounded-full',
          'bg-[radial-gradient(circle,var(--color-accent-subtle),transparent_70%)]',
          'opacity-0 transition-opacity duration-(--duration-base) group-hover:opacity-100',
        )}
      />

      <div className="relative flex items-start justify-between gap-3">
        <p className="text-label-sm font-[510] text-fg-subtle">{label}</p>
        {delta !== undefined ? (
          <span
            className={cn(
              'inline-flex shrink-0 items-center gap-0.5 rounded-full px-1.5 py-0.5',
              'text-caption font-[510] tabular',
              direction === 'flat'
                ? 'bg-neutral-surface text-fg-subtle'
                : good
                  ? 'bg-success-surface text-success'
                  : 'bg-danger-surface text-danger',
            )}
          >
            <DeltaIcon aria-hidden className="size-3" />
            {delta > 0 ? '+' : ''}
            {(delta * 100).toFixed(1)}%
          </span>
        ) : null}
      </div>

      <motion.p className="relative mt-2 tabular text-title-1 leading-none font-[510] tracking-[-0.88px] text-fg">
        {text}
      </motion.p>

      {/*
       * The accessible value, stated once in plain text. The counting figure above is a
       * MotionValue that a screen reader would otherwise read mid-count, or not at all.
       */}
      <span className="sr-only">
        {label}: {format(value)}
        {delta !== undefined
          ? `, ${direction === 'flat' ? 'unchanged' : direction} ${Math.abs(delta * 100).toFixed(1)} percent`
          : ''}
      </span>

      {hint ? <p className="relative mt-1 text-label-sm text-fg-subtle">{hint}</p> : null}

      {series ? (
        <div className="relative mt-3">
          <Sparkline
            points={series}
            tone={good ? 'success' : direction === 'flat' ? 'muted' : 'danger'}
          />
        </div>
      ) : null}
    </motion.div>
  );
}
