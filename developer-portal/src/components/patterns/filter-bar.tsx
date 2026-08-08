'use client';

import { AnimatePresence, motion } from 'framer-motion';
import { Check, Plus, X } from 'lucide-react';
import * as React from 'react';

import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { duration, ease } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * Filter chips (M23.1 redesign) — presentation only; M23.6 binds them to the URL.
 *
 * ── What the animation is for ─────────────────────────────────────────────────────────
 *
 * Chips enter and leave with `AnimatePresence` and a `layout` prop, so adding a filter pushes
 * its neighbours aside rather than making the row jump. That matters more than it sounds: the
 * "Clear all" button sits at the end of the row, and without layout animation it teleports every
 * time a chip appears — putting a destructive control under a cursor that was aimed at
 * something else.
 *
 * The scale on enter is 0.9, larger than the 0.98 used for overlays. A chip is small enough that
 * a 2% change is invisible; the point of the animation is to say *this is new*, and at chip size
 * that needs a little more travel.
 *
 * ── Why the count is on the trigger ───────────────────────────────────────────────────
 *
 * A filter row that has scrolled out of view still needs to announce that it is filtering
 * something, otherwise an empty table reads as "no data" rather than "no matches" — the single
 * most common way a filtered list is misread.
 */

export interface FilterOption {
  readonly id: string;
  readonly label: string;
  readonly group: string;
}

export function FilterBar({
  options,
  selected,
  onChange,
  className,
}: {
  options: readonly FilterOption[];
  selected: readonly string[];
  onChange: (next: readonly string[]) => void;
  className?: string | undefined;
}) {
  const byId = React.useMemo(() => new Map(options.map((o) => [o.id, o])), [options]);
  const groups = React.useMemo(() => {
    const map = new Map<string, FilterOption[]>();
    for (const option of options) {
      map.set(option.group, [...(map.get(option.group) ?? []), option]);
    }
    return [...map.entries()];
  }, [options]);

  const toggle = (id: string) =>
    onChange(selected.includes(id) ? selected.filter((s) => s !== id) : [...selected, id]);

  return (
    <div className={cn('flex flex-wrap items-center gap-2', className)}>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="secondary" size="sm">
            <Plus />
            Filter
            {selected.length > 0 ? (
              <span className="ml-0.5 rounded-full bg-accent-subtle px-1.5 text-caption font-[510] tabular text-accent-text">
                {selected.length}
              </span>
            ) : null}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="min-w-52">
          {groups.map(([group, entries]) => (
            <div key={group}>
              <DropdownMenuLabel>{group}</DropdownMenuLabel>
              {entries.map((option) => (
                <DropdownMenuCheckboxItem
                  key={option.id}
                  checked={selected.includes(option.id)}
                  onCheckedChange={() => toggle(option.id)}
                  onSelect={(e) => e.preventDefault()}
                >
                  {option.label}
                </DropdownMenuCheckboxItem>
              ))}
            </div>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>

      <AnimatePresence mode="popLayout" initial={false}>
        {selected.map((id) => {
          const option = byId.get(id);
          if (!option) return null;
          return (
            <motion.button
              key={id}
              layout
              type="button"
              onClick={() => toggle(id)}
              initial={{ opacity: 0, scale: 0.9 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.9 }}
              transition={{ duration: duration.fast, ease: ease.outQuart }}
              aria-label={`Remove filter ${option.label}`}
              className={cn(
                'group gpu inline-flex items-center gap-1.5 rounded-full py-0.5 pr-1.5 pl-2',
                'bg-accent-subtle text-label-sm font-[510] text-accent-text',
                'transition-colors duration-(--duration-fast) hover:bg-accent-subtle/70',
              )}
            >
              <Check aria-hidden className="size-3" />
              <span className="text-fg-subtle">{option.group}</span>
              <span>{option.label}</span>
              <X
                aria-hidden
                className="size-3 text-fg-subtle transition-colors group-hover:text-fg"
              />
            </motion.button>
          );
        })}
      </AnimatePresence>

      <AnimatePresence initial={false}>
        {selected.length > 0 ? (
          <motion.div
            layout
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: duration.fast }}
          >
            <Button variant="ghost" size="sm" onClick={() => onChange([])}>
              Clear all
            </Button>
          </motion.div>
        ) : null}
      </AnimatePresence>
    </div>
  );
}
