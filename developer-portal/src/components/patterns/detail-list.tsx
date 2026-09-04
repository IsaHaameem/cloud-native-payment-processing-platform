import * as React from 'react';

import { cn } from '@/lib/utils';

export interface DetailRow {
  readonly label: string;
  readonly value: React.ReactNode;
  /** Render the value in the mono stack (ids, tokens, timestamps). */
  readonly mono?: boolean;
}

/**
 * The key/value panel every detail page uses for its technical facts (frontend build).
 *
 * A `<dl>` — the correct element, and the one that lets a screen reader pair each term with its
 * definition. Labels are `label-sm` in `fg-subtle`; values are `label` in `fg-muted`, mono when
 * asked. Rows wrap on a narrow column rather than truncating, because a payment id that is cut
 * off is useless.
 */
export function DetailList({
  rows,
  className,
  columns = 1,
}: {
  rows: readonly DetailRow[];
  className?: string | undefined;
  columns?: 1 | 2;
}) {
  return (
    <dl className={cn('grid gap-x-6 gap-y-3', columns === 2 && 'sm:grid-cols-2', className)}>
      {rows.map((row) => (
        <div key={row.label} className="min-w-0">
          <dt className="text-label-sm text-fg-subtle">{row.label}</dt>
          <dd
            className={cn('mt-0.5 text-label break-words text-fg-muted', row.mono && 'font-mono')}
          >
            {row.value}
          </dd>
        </div>
      ))}
    </dl>
  );
}
