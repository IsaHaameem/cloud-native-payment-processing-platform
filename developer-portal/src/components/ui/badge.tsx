import { cva, type VariantProps } from 'class-variance-authority';
import * as React from 'react';

import { cn } from '@/lib/utils';

/**
 * A status chip (M23.1, redesigned to the Linear system).
 *
 * **Pill, not rounded-rectangle.** The reference reserves `radius-pill` for exactly this —
 * "pill shapes reserved for badges and status chips" — and it is the one place the system
 * permits a shape that differs from everything else on the page.
 *
 * **12px / weight 510**, the extracted `label-small` role: "Compact labels, badges, metadata
 * chips". Each tone pairs a foreground with a tinted surface from one token family so contrast
 * holds in both themes without a second set of values.
 *
 * A caller that encodes meaning must also carry a label — colour is never the only channel,
 * which is what keeps the status vocabulary readable without colour perception. The optional
 * `dot` adds a second visual channel for the cases where the label alone is ambiguous.
 */
const badgeVariants = cva(
  [
    'inline-flex items-center gap-1.5 rounded-full px-2 py-0.5',
    'text-label-sm font-[510] whitespace-nowrap',
  ],
  {
    variants: {
      tone: {
        neutral: 'bg-neutral-surface text-fg-subtle',
        success: 'bg-success-surface text-success',
        warning: 'bg-warning-surface text-warning',
        danger: 'bg-danger-surface text-danger',
        info: 'bg-info-surface text-info',
        accent: 'bg-accent-subtle text-accent-text',
        test: 'bg-mode-test-surface text-mode-test ring-1 ring-mode-test-border ring-inset',
        outline: 'text-fg-subtle ring-1 ring-border ring-inset',
      },
    },
    defaultVariants: { tone: 'neutral' },
  },
);

export interface BadgeProps
  extends React.HTMLAttributes<HTMLSpanElement>, VariantProps<typeof badgeVariants> {
  /** A 5px dot in the current colour — a second channel for a status the label alone leaves ambiguous. */
  dot?: boolean | undefined;
}

export function Badge({ className, tone, dot = false, children, ...props }: BadgeProps) {
  return (
    <span className={cn(badgeVariants({ tone }), className)} {...props}>
      {dot ? (
        <span aria-hidden className="size-[5px] shrink-0 rounded-full bg-current opacity-80" />
      ) : null}
      {children}
    </span>
  );
}

export { badgeVariants };
