'use client';

import { Check } from 'lucide-react';
import * as React from 'react';

import { cn } from '@/lib/utils';

export interface Option {
  readonly id: string;
  readonly label: string;
  readonly hint?: string | undefined;
}

/**
 * A grid of selectable option cards — the wizard's "pick one" / "pick many" control.
 *
 * `multi={false}` is a radio group; `multi` is a set of checkboxes. Keyboard: each option is a
 * real button, Space/Enter toggles. One control, so the wizard's three steps look identical.
 */
export function OptionGrid({
  options,
  value,
  onChange,
  multi = false,
  columns = 2,
  ariaLabel,
}: {
  options: readonly Option[];
  value: readonly string[];
  onChange: (next: string[]) => void;
  multi?: boolean;
  columns?: 2 | 3;
  ariaLabel: string;
}) {
  const toggle = (id: string) => {
    if (multi) {
      onChange(value.includes(id) ? value.filter((v) => v !== id) : [...value, id]);
    } else {
      onChange([id]);
    }
  };

  return (
    <div
      role={multi ? 'group' : 'radiogroup'}
      aria-label={ariaLabel}
      className={cn(
        'grid gap-2.5',
        columns === 3 ? 'sm:grid-cols-2 lg:grid-cols-3' : 'sm:grid-cols-2',
      )}
    >
      {options.map((opt) => {
        const selected = value.includes(opt.id);
        return (
          <button
            key={opt.id}
            type="button"
            role={multi ? 'checkbox' : 'radio'}
            aria-checked={selected}
            onClick={() => toggle(opt.id)}
            className={cn(
              'flex items-start gap-2.5 rounded-lg p-3 text-left ring-1 ring-inset transition-colors duration-(--duration-fast)',
              selected
                ? 'bg-accent-subtle ring-accent-ring'
                : 'bg-surface ring-border hover:bg-surface-hover hover:ring-border-strong',
            )}
          >
            <span
              aria-hidden
              className={cn(
                'mt-0.5 flex size-4 shrink-0 items-center justify-center rounded-full ring-1 ring-inset',
                selected ? 'bg-accent text-fg-on-accent ring-accent' : 'ring-border-strong',
                multi ? 'rounded-[5px]' : 'rounded-full',
              )}
            >
              {selected ? <Check className="size-2.5" strokeWidth={3} /> : null}
            </span>
            <span className="min-w-0">
              <span className="block text-label font-[510] text-fg">{opt.label}</span>
              {opt.hint ? (
                <span className="mt-0.5 block text-label-sm text-fg-subtle">{opt.hint}</span>
              ) : null}
            </span>
          </button>
        );
      })}
    </div>
  );
}
