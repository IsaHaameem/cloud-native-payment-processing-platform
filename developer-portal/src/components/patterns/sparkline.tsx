'use client';

import { motion } from 'framer-motion';
import * as React from 'react';

import { cn } from '@/lib/utils';

/**
 * A sparkline (M23.1 redesign) — the trend beside a metric.
 *
 * ── Drawn, not charted ────────────────────────────────────────────────────────────────
 *
 * An inline SVG with no charting library. A sparkline has no axes, no legend, no tooltip and no
 * interaction; every one of those is what a chart library is *for*, so importing one here would
 * be paying its whole weight for a `<path>`. M24's real charts are a separate decision with a
 * separate justification.
 *
 * ── The line draws itself ─────────────────────────────────────────────────────────────
 *
 * `pathLength` from 0 to 1 animates the stroke as though it were being drawn left to right,
 * which is the one motion that says "this is a series over time" without a label saying so. It
 * is a single interpolated attribute — no layout, no paint of anything but the stroke — so it
 * composites cheaply even with a dozen on screen.
 *
 * The fill fades in behind it rather than wiping, because a gradient that grows edge-first reads
 * as a progress bar.
 *
 * ── Not decoration ────────────────────────────────────────────────────────────────────
 *
 * `aria-hidden`, always. The trend it shows is never the only place the number appears — the
 * metric card states the value and its change in text. A screen-reader user loses nothing here,
 * which is the test for whether a graphic is decorative.
 */
export function Sparkline({
  points,
  className,
  tone = 'accent',
  animate = true,
}: {
  /** Raw values, oldest first. Normalised internally, so callers pass whatever they have. */
  points: readonly number[];
  className?: string | undefined;
  tone?: 'accent' | 'success' | 'danger' | 'muted' | undefined;
  animate?: boolean | undefined;
}) {
  const id = React.useId();
  const width = 100;
  const height = 32;

  const { line, area } = React.useMemo(() => {
    if (points.length < 2) return { line: '', area: '' };
    const min = Math.min(...points);
    const max = Math.max(...points);
    // A flat series would divide by zero; drawing it through the middle is the honest render.
    const span = max - min || 1;
    const step = width / (points.length - 1);
    const coords = points.map((value, index) => {
      const x = index * step;
      const y = height - ((value - min) / span) * (height - 4) - 2;
      return `${x.toFixed(2)},${y.toFixed(2)}`;
    });
    return {
      line: `M${coords.join(' L')}`,
      area: `M0,${height} L${coords.join(' L')} L${width},${height} Z`,
    };
  }, [points]);

  if (!line) return null;

  const stroke = {
    accent: 'var(--accent-text)',
    success: 'var(--success)',
    danger: 'var(--danger)',
    muted: 'var(--fg-subtle)',
  }[tone];

  return (
    <svg
      aria-hidden
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      className={cn('h-8 w-full overflow-visible', className)}
    >
      <defs>
        <linearGradient id={`spark-${id}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={stroke} stopOpacity="0.22" />
          <stop offset="100%" stopColor={stroke} stopOpacity="0" />
        </linearGradient>
      </defs>

      <motion.path
        d={area}
        fill={`url(#spark-${id})`}
        initial={animate ? { opacity: 0 } : false}
        animate={animate ? { opacity: 1 } : {}}
        transition={{ duration: 0.4, delay: 0.25 }}
      />
      <motion.path
        d={line}
        fill="none"
        stroke={stroke}
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
        vectorEffect="non-scaling-stroke"
        initial={animate ? { pathLength: 0 } : false}
        animate={animate ? { pathLength: 1 } : {}}
        transition={{ duration: 0.7, ease: [0.16, 1, 0.3, 1] }}
      />
    </svg>
  );
}
