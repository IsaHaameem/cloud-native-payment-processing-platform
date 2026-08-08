'use client';

import { AnimatePresence, motion, type HTMLMotionProps } from 'framer-motion';
import { ChevronRight } from 'lucide-react';
import * as React from 'react';

import { duration, ease, expandVariants, listItemVariants, listVariants } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * The table shell (M23.1 redesign) — presentation only; M23.6 gives it data, filters and cursor
 * pagination.
 *
 * ── What the reference dictates ───────────────────────────────────────────────────────
 *
 * "High-density product UI". Rows are 36px, the header is a 10px uppercase `caption`, and there
 * are **no vertical rules** — columns are separated by alignment and space, which is what keeps
 * a wide table from reading as a spreadsheet. Horizontal hairlines only, in `border-subtle`.
 *
 * ── Why rows stagger, and why only barely ─────────────────────────────────────────────
 *
 * `staggerChildren: 0.03` finishes a twelve-row table in under half a second, so it reads as one
 * movement rather than a sequence. Slower is a performance the user has to sit through on every
 * navigation, which is how motion turns into latency.
 *
 * The stagger runs on mount only. When M23.6 adds filtering, re-animating on every keystroke
 * would be exactly that mistake — so the variants are applied here, at the table, and a future
 * filtered re-render must not re-key this element.
 *
 * ── The scroll container is here, not on the page ─────────────────────────────────────
 *
 * A wide table scrolls inside itself. The page body never scrolls sideways — that rule holds
 * across the portal, and a table is the component most likely to break it.
 */

export function DataTable({ children, className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('overflow-x-auto rounded-lg bg-surface ring-hairline', className)}
      {...props}
    >
      <table className="w-full border-collapse text-left">{children}</table>
    </div>
  );
}

export function DataTableHead({ columns }: { columns: readonly string[] }) {
  return (
    <thead>
      <tr className="border-b border-border-subtle">
        {columns.map((column) => (
          <th
            key={column}
            scope="col"
            className="px-4 py-2.5 text-caption font-[510] tracking-[0.04em] text-fg-subtle uppercase"
          >
            {column}
          </th>
        ))}
      </tr>
    </thead>
  );
}

export function DataTableBody({ children }: { children: React.ReactNode }) {
  return (
    <motion.tbody variants={listVariants} initial="hidden" animate="visible">
      {children}
    </motion.tbody>
  );
}

export function DataTableRow({
  children,
  className,
  ...props
}: Omit<HTMLMotionProps<'tr'>, 'ref'>) {
  return (
    <motion.tr
      variants={listItemVariants}
      className={cn(
        'border-b border-border-subtle last:border-0',
        'transition-colors duration-(--duration-instant) hover:bg-surface-hover',
        className,
      )}
      {...props}
    >
      {children}
    </motion.tr>
  );
}

export function DataTableCell({
  className,
  ...props
}: React.TdHTMLAttributes<HTMLTableCellElement>) {
  return <td className={cn('h-9 px-4 text-label text-fg-muted', className)} {...props} />;
}

/**
 * A row that opens a detail panel beneath itself.
 *
 * ── Why expanding rather than navigating ──────────────────────────────────────────────
 *
 * Scanning a list and needing one more field about several rows is the common case, and a round
 * trip to a detail page and back loses the scroll position and the filters every time. Expanding
 * keeps the list where it is. It does not replace the detail page — M23.7 still builds that, and
 * this panel links to it.
 *
 * ── The one place height is animated ──────────────────────────────────────────────────
 *
 * `expandVariants` animates `height`, which the rest of the system refuses to do. It is
 * unavoidable here: the whole point is that the rows below move down, and no transform can push
 * a sibling. Framer measures the natural height itself, so the browser is not recalculating
 * layout from scratch on every frame.
 *
 * ── Accessibility ─────────────────────────────────────────────────────────────────────
 *
 * The trigger row is a real `<button>` in its first cell — not a click handler on the `<tr>`,
 * which is unreachable by keyboard and unannounced by a screen reader. It carries
 * `aria-expanded` and `aria-controls`, and the panel row is `hidden` from the accessibility tree
 * while collapsed rather than merely being zero-height.
 */
export function DataTableExpandableRow({
  summary,
  detail,
  columnCount,
  defaultOpen = false,
  label,
}: {
  /** The cells of the collapsed row. The first one gets the disclosure control prepended. */
  summary: React.ReactNode;
  detail: React.ReactNode;
  /** So the detail row's single cell spans the full table. */
  columnCount: number;
  defaultOpen?: boolean | undefined;
  /** Names the row for the screen reader, e.g. "payment pay_3fA9kQ". */
  label: string;
}) {
  const [open, setOpen] = React.useState(defaultOpen);
  const panelId = React.useId();

  return (
    <>
      <motion.tr
        variants={listItemVariants}
        className={cn(
          'border-b border-border-subtle',
          'transition-colors duration-(--duration-instant) hover:bg-surface-hover',
          open && 'bg-surface-hover',
        )}
      >
        <td className="h-9 pr-0 pl-2">
          <button
            type="button"
            onClick={() => setOpen((o) => !o)}
            aria-expanded={open}
            aria-controls={panelId}
            className={cn(
              'flex size-5 items-center justify-center rounded-sm text-fg-subtle',
              'transition-colors duration-(--duration-fast) hover:bg-surface-active hover:text-fg',
            )}
          >
            <motion.span
              aria-hidden
              animate={{ rotate: open ? 90 : 0 }}
              transition={{ duration: duration.fast, ease: ease.outQuart }}
              className="leading-none"
            >
              <ChevronRight className="size-3.5" />
            </motion.span>
            <span className="sr-only">
              {open ? 'Collapse' : 'Expand'} {label}
            </span>
          </button>
        </td>
        {summary}
      </motion.tr>

      <tr aria-hidden={!open} className={cn(!open && 'border-0')}>
        <td colSpan={columnCount + 1} className="p-0">
          <AnimatePresence initial={false}>
            {open ? (
              <motion.div
                id={panelId}
                variants={expandVariants}
                initial="hidden"
                animate="visible"
                exit="exit"
                className="overflow-hidden"
              >
                <div className="border-b border-border-subtle bg-surface-inset px-4 py-3">
                  {detail}
                </div>
              </motion.div>
            ) : null}
          </AnimatePresence>
        </td>
      </tr>
    </>
  );
}
