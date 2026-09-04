'use client';

import { motion } from 'framer-motion';
import * as React from 'react';

import { duration, ease } from '@/lib/motion';
import { cn } from '@/lib/utils';

export interface TabItem {
  readonly id: string;
  readonly label: string;
  readonly count?: number | undefined;
}

/**
 * Underline tabs (frontend build).
 *
 * The reference's tab style: a label row with a 2px accent underline that *travels* between the
 * active tab and the next via a shared `layoutId`, the same motion the sidebar's active rail
 * uses. Controlled — the caller owns `value` — because on the pages that use these (Analytics,
 * Webhooks, Docs) the tab is usually reflected in the URL.
 *
 * Keyboard: the row is a `tablist`, each tab a `tab`, arrow keys move between them. The panels
 * are the caller's problem; this renders only the control.
 */
export function Tabs({
  items,
  value,
  onValueChange,
  className,
  'aria-label': ariaLabel,
}: {
  items: readonly TabItem[];
  value: string;
  onValueChange: (id: string) => void;
  className?: string | undefined;
  'aria-label'?: string | undefined;
}) {
  const refs = React.useRef<Record<string, HTMLButtonElement | null>>({});

  function onKeyDown(event: React.KeyboardEvent) {
    const index = items.findIndex((item) => item.id === value);
    if (index < 0) return;
    let next = index;
    if (event.key === 'ArrowRight') next = (index + 1) % items.length;
    else if (event.key === 'ArrowLeft') next = (index - 1 + items.length) % items.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = items.length - 1;
    else return;
    event.preventDefault();
    const target = items[next];
    if (target) {
      onValueChange(target.id);
      refs.current[target.id]?.focus();
    }
  }

  return (
    <div
      role="tablist"
      aria-label={ariaLabel}
      onKeyDown={onKeyDown}
      className={cn('flex items-center gap-1 border-b border-border-subtle', className)}
    >
      {items.map((item) => {
        const active = item.id === value;
        return (
          <button
            key={item.id}
            ref={(el) => {
              refs.current[item.id] = el;
            }}
            type="button"
            role="tab"
            aria-selected={active}
            tabIndex={active ? 0 : -1}
            onClick={() => onValueChange(item.id)}
            className={cn(
              'relative flex items-center gap-1.5 px-3 py-2.5 text-label font-[510] whitespace-nowrap',
              'transition-colors duration-(--duration-fast)',
              active ? 'text-fg' : 'text-fg-subtle hover:text-fg',
            )}
          >
            {item.label}
            {item.count !== undefined ? (
              <span className="tabular rounded-full bg-surface-active px-1.5 text-caption font-[510] text-fg-subtle">
                {item.count}
              </span>
            ) : null}
            {active ? (
              <motion.span
                aria-hidden
                layoutId="tabs-underline"
                className="absolute inset-x-0 -bottom-px h-0.5 rounded-full bg-accent"
                transition={{ duration: duration.base, ease: ease.outQuart }}
              />
            ) : null}
          </button>
        );
      })}
    </div>
  );
}
