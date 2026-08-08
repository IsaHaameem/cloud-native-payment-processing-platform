import * as React from 'react';

import { cn } from '@/lib/utils';

/**
 * A text field (M23.1, redesigned to the Linear system).
 *
 * 6px radius and a hairline inset ring, matched to the medium button so a field and a button
 * sitting side by side share an edge treatment and a height. Placeholder text uses
 * `text-tertiary`, the role the reference names for it.
 *
 * The focus ring comes from the global `:focus-visible` rule — a 3px outline at offset 0, which
 * is the reference's own interaction evidence — so it is not restated here and cannot drift.
 */
export function Input({ className, type = 'text', ...props }: React.ComponentProps<'input'>) {
  return (
    <input
      type={type}
      className={cn(
        'flex h-8 w-full rounded-md bg-surface-inset px-2.5 ring-hairline',
        'text-body text-fg placeholder:text-fg-subtle',
        'transition-[background-color,box-shadow] duration-(--duration-fast)',
        'hover:bg-surface-hover',
        'disabled:cursor-not-allowed disabled:opacity-40',
        'file:border-0 file:bg-transparent file:text-label file:font-[510]',
        className,
      )}
      {...props}
    />
  );
}
