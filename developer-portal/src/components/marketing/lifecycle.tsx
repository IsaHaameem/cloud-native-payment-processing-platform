'use client';

import { motion } from 'framer-motion';

import { duration, ease } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * The payment lifecycle (M23.2a).
 *
 * ── It is the real state machine ──────────────────────────────────────────────────────
 *
 * `CREATED → AUTHORIZED → CAPTURED → REFUNDED`, with `PARTIALLY_REFUNDED` looping back on
 * itself, and `VOIDED` and `FAILED` as terminal exits — transcribed from `PaymentStatus`'s own
 * transition table, in its own wire spelling. The branches are shown rather than hidden: a
 * diagram that draws only the happy path is a diagram that will be contradicted by the first
 * declined card, and an audience evaluating a payments API is looking for exactly the states a
 * marketing diagram usually omits.
 *
 * ── The connector draws itself once ───────────────────────────────────────────────────
 *
 * `pathLength` from 0 to 1 as the section scrolls into view: the line is *drawn* between the
 * states rather than fading in, which is the one animation that actually says "these happen in
 * this order". It runs once, and `MotionConfig reducedMotion="user"` reduces it to the finished
 * line — which is the correct still frame, not a broken one.
 *
 * ── Why an inline SVG and not four divs with borders ──────────────────────────────────
 *
 * A CSS connector cannot be drawn progressively, and the layout that fakes it (a growing
 * `width`) animates a property that forces layout on every frame. `pathLength` is composited.
 */

interface Stage {
  readonly name: string;
  readonly description: string;
}

const STAGES: readonly Stage[] = [
  { name: 'created', description: 'The payment exists. Nothing has been charged.' },
  { name: 'authorized', description: 'Funds are held on the card, not yet taken.' },
  { name: 'captured', description: 'The hold is settled. The money has moved.' },
  { name: 'refunded', description: 'Fully returned, and reversed in the ledger.' },
];

const EXITS: readonly Stage[] = [
  { name: 'partially_refunded', description: 'Some of the capture returned; more may follow.' },
  { name: 'voided', description: 'Cancelled before capture. Terminal.' },
  { name: 'failed', description: 'Authorization was declined. Terminal.' },
];

export function Lifecycle() {
  return (
    <div>
      <ol className="relative grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {STAGES.map((stage, index) => (
          <motion.li
            key={stage.name}
            initial={{ opacity: 0, y: 6 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true, amount: 0.6 }}
            transition={{
              duration: duration.base,
              ease: ease.outQuart,
              delay: 0.12 * index,
            }}
            className="relative"
          >
            {/*
             * One connector per step, from this step's plate to the next column's — rather than
             * one line spanning the row.
             *
             * The row-spanning version was an SVG whose `pathLength` animation is implemented by
             * Framer Motion *through* the dash properties, measured in the viewBox's user units.
             * With `preserveAspectRatio="none"` the horizontal scale is roughly 11× the vertical
             * one, so the computed dash length bore no relation to the rendered width and the
             * line drew as a stub. This has no viewBox and no scaling: a 1px element with a
             * `scaleX` transform, which is composited and cannot be wrong about its own length.
             *
             * Per-step also fixes what the single line got wrong about the layout: the plates are
             * at the *left* of each column, not centred in it, so a line spanning the row's
             * middle connected nothing in particular.
             *
             * Only at `lg`, where the four stages are genuinely in a row. Below that they wrap,
             * and a connector drawn between wrapped items points at the wrong thing.
             */}
            {index < STAGES.length - 1 ? (
              <motion.span
                aria-hidden
                initial={{ scaleX: 0 }}
                whileInView={{ scaleX: 1 }}
                viewport={{ once: true, amount: 0.6 }}
                transition={{
                  duration: duration.base,
                  ease: ease.outQuart,
                  delay: 0.12 * index + 0.1,
                }}
                /*
                 * `left-16` is the label's own left edge — 52px plate plus the 12px `gap-3` —
                 * so the segment begins exactly where the opaque label starts and is masked
                 * until the word ends. Four pixels earlier and a stub appears before the
                 * label, which reads as a rendering artefact rather than as a connector.
                 *
                 * `-right-4` reaches across the grid's own 16px gap to the next column's edge.
                 */
                className="pointer-events-none absolute top-[26px] -right-4 left-16 hidden h-px origin-left bg-border-strong lg:block"
              />
            ) : null}

            <div className="flex items-center gap-3">
              <span
                className={cn(
                  'flex size-[52px] shrink-0 items-center justify-center rounded-lg',
                  'bg-surface text-label font-[510] ring-hairline',
                  // The last state is the only one that gets the accent: it is the end of the
                  // sequence, and one highlight in a row of four reads as a destination.
                  index === STAGES.length - 1 ? 'text-accent-text' : 'text-fg-subtle',
                )}
              >
                {index + 1}
              </span>
              {/*
               * Opaque, because the connector runs along the vertical centre of the plate —
               * which is also this label's baseline band, so without a background the line
               * strikes through the state name. Masking the label is what lets the connector
               * start immediately after the plate (where it visually belongs) rather than after
               * a fixed offset guessed to clear the longest word.
               */}
              <span className="relative bg-canvas pr-3 font-mono text-label text-fg">
                {stage.name}
              </span>
            </div>
            <p className="mt-3 text-label text-fg-subtle">{stage.description}</p>
          </motion.li>
        ))}
      </ol>

      <div className="mt-10 border-t border-border-subtle pt-6">
        <p className="text-label font-[510] text-fg">And the states a happy path leaves out</p>
        <dl className="mt-4 grid gap-4 sm:grid-cols-3">
          {EXITS.map((exit) => (
            <div key={exit.name}>
              <dt className="font-mono text-label-sm text-fg-muted">{exit.name}</dt>
              <dd className="mt-1 text-label text-fg-subtle">{exit.description}</dd>
            </div>
          ))}
        </dl>
      </div>
    </div>
  );
}
