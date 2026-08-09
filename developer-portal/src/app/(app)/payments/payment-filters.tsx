'use client';

import { AnimatePresence, motion } from 'framer-motion';
import { Check, ChevronDown, SlidersHorizontal, X } from 'lucide-react';
import * as React from 'react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Input } from '@/components/ui/input';
import {
  COMMON_CURRENCIES,
  NO_FILTERS,
  PAYMENT_STATUSES,
  type PaymentFilters,
} from '@/lib/payments/filters';

import { statusLabel } from './status-badge';

/**
 * The payments list's filter controls (M23.6).
 *
 * ── Applied on commit, not on keystroke ───────────────────────────────────────────────
 *
 * The categorical filters — status and currency — apply the moment they are toggled, because a
 * toggle *is* the decision. The range fields do not: an amount or a date is half-typed for most of
 * its life, and querying `amount_min=1` on the way to `amount_min=10000` spends a request on a
 * value the user never meant and briefly shows them a wrong list. So the ranges commit on Enter or
 * on blur, which is also when a partial date stops being ambiguous.
 *
 * That is the difference between feeling immediate and being noisy. It costs nothing in perceived
 * speed because the toggles — the filters people actually reach for first — are instant.
 *
 * ── The chips are the state ───────────────────────────────────────────────────────────
 *
 * Every applied filter shows as a chip that removes itself. A filtered list whose controls are
 * behind a dropdown is a list a user forgets is filtered, and then reports as missing data.
 */

export function PaymentFilterBar({
  filters,
  onChange,
  count,
}: {
  filters: PaymentFilters;
  onChange: (next: PaymentFilters) => void;
  count: number;
}) {
  const toggleStatus = (status: string) =>
    onChange({
      ...filters,
      statuses: filters.statuses.includes(status)
        ? filters.statuses.filter((value) => value !== status)
        : [...filters.statuses, status],
    });

  const setCurrency = (currency: string | undefined) => onChange({ ...filters, currency });

  return (
    <div className="flex flex-col gap-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="secondary" size="sm">
              <SlidersHorizontal />
              Filter
              {count > 0 ? (
                <span className="ml-0.5 rounded-full bg-accent-subtle px-1.5 text-caption font-[510] tabular text-accent-text">
                  {count}
                </span>
              ) : null}
              <ChevronDown />
            </Button>
          </DropdownMenuTrigger>

          <DropdownMenuContent align="start" className="min-w-56">
            <DropdownMenuLabel>Status</DropdownMenuLabel>
            {PAYMENT_STATUSES.map((status) => (
              <DropdownMenuCheckboxItem
                key={status}
                checked={filters.statuses.includes(status)}
                onCheckedChange={() => toggleStatus(status)}
                onSelect={(event) => event.preventDefault()}
              >
                {statusLabel(status)}
              </DropdownMenuCheckboxItem>
            ))}

            <DropdownMenuSeparator />
            <DropdownMenuLabel>Currency</DropdownMenuLabel>
            {COMMON_CURRENCIES.map((currency) => (
              <DropdownMenuCheckboxItem
                key={currency}
                checked={filters.currency === currency}
                onCheckedChange={() =>
                  setCurrency(filters.currency === currency ? undefined : currency)
                }
                onSelect={(event) => event.preventDefault()}
              >
                {currency}
              </DropdownMenuCheckboxItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>

        <RangeFields filters={filters} onChange={onChange} />

        {count > 0 ? (
          <Button variant="ghost" size="sm" onClick={() => onChange(NO_FILTERS)}>
            <X />
            Clear all
          </Button>
        ) : null}
      </div>

      <AnimatePresence initial={false}>
        {count > 0 ? (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: 'auto' }}
            exit={{ opacity: 0, height: 0 }}
            transition={{ duration: 0.15 }}
            className="flex flex-wrap items-center gap-1.5 overflow-hidden"
          >
            {filters.statuses.map((status) => (
              <Chip key={status} onRemove={() => toggleStatus(status)}>
                Status: {statusLabel(status)}
              </Chip>
            ))}
            {filters.currency ? (
              <Chip onRemove={() => setCurrency(undefined)}>Currency: {filters.currency}</Chip>
            ) : null}
            {filters.amountMin !== undefined || filters.amountMax !== undefined ? (
              <Chip
                onRemove={() =>
                  onChange({ ...filters, amountMin: undefined, amountMax: undefined })
                }
              >
                {/* Minor units, named as such — the API's unit, and the only one shown here. */}
                Amount: {filters.amountMin ?? 0}–{filters.amountMax ?? '∞'} minor
              </Chip>
            ) : null}
            {filters.createdAfter || filters.createdBefore ? (
              <Chip
                onRemove={() =>
                  onChange({ ...filters, createdAfter: undefined, createdBefore: undefined })
                }
              >
                Created: {filters.createdAfter?.slice(0, 10) ?? 'any'} →{' '}
                {filters.createdBefore?.slice(0, 10) ?? 'now'}
              </Chip>
            ) : null}
            {filters.metadataKey ? (
              <Chip
                onRemove={() =>
                  onChange({ ...filters, metadataKey: undefined, metadataValue: undefined })
                }
              >
                <span className="font-mono">
                  {filters.metadataKey}={filters.metadataValue}
                </span>
              </Chip>
            ) : null}
          </motion.div>
        ) : null}
      </AnimatePresence>
    </div>
  );
}

function Chip({ children, onRemove }: { children: React.ReactNode; onRemove: () => void }) {
  return (
    <Badge tone="outline" className="gap-1 pr-1">
      {children}
      <button
        type="button"
        onClick={onRemove}
        aria-label="Remove filter"
        className="rounded-sm p-0.5 text-fg-muted transition-colors hover:bg-surface-hover hover:text-fg"
      >
        <X className="size-3" />
      </button>
    </Badge>
  );
}

/**
 * The range and metadata inputs.
 *
 * Held in local state and pushed up on commit, which is what makes typing feel like typing. The
 * local copy is re-seeded whenever the applied filters change, so the back button and "clear all"
 * are reflected in the fields rather than leaving stale text behind them.
 */
function RangeFields({
  filters,
  onChange,
}: {
  filters: PaymentFilters;
  onChange: (next: PaymentFilters) => void;
}) {
  const [open, setOpen] = React.useState(false);
  const [draft, setDraft] = React.useState(() => toDraft(filters));

  React.useEffect(() => setDraft(toDraft(filters)), [filters]);

  const commit = () => {
    onChange({
      ...filters,
      amountMin: wholeNumber(draft.amountMin),
      amountMax: wholeNumber(draft.amountMax),
      createdAfter: isoDate(draft.createdAfter),
      createdBefore: isoDate(draft.createdBefore, true),
      metadataKey: draft.metadataKey.trim() || undefined,
      metadataValue: draft.metadataKey.trim() ? draft.metadataValue.trim() : undefined,
    });
  };

  return (
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger asChild>
        <Button variant="secondary" size="sm">
          Amount, date, metadata
          <ChevronDown />
        </Button>
      </DropdownMenuTrigger>

      <DropdownMenuContent align="start" className="w-80 p-3">
        <form
          onSubmit={(event) => {
            event.preventDefault();
            commit();
            setOpen(false);
          }}
          className="flex flex-col gap-3"
        >
          <Pair label="Amount (minor units)">
            <Input
              value={draft.amountMin}
              onChange={(e) => setDraft({ ...draft, amountMin: e.target.value })}
              inputMode="numeric"
              placeholder="min"
              aria-label="Minimum amount in minor units"
            />
            <Input
              value={draft.amountMax}
              onChange={(e) => setDraft({ ...draft, amountMax: e.target.value })}
              inputMode="numeric"
              placeholder="max"
              aria-label="Maximum amount in minor units"
            />
          </Pair>

          <Pair label="Created">
            <Input
              type="date"
              value={draft.createdAfter}
              onChange={(e) => setDraft({ ...draft, createdAfter: e.target.value })}
              aria-label="Created on or after"
            />
            <Input
              type="date"
              value={draft.createdBefore}
              onChange={(e) => setDraft({ ...draft, createdBefore: e.target.value })}
              aria-label="Created on or before"
            />
          </Pair>

          <Pair label="Metadata">
            <Input
              value={draft.metadataKey}
              onChange={(e) => setDraft({ ...draft, metadataKey: e.target.value })}
              placeholder="key"
              aria-label="Metadata key"
            />
            <Input
              value={draft.metadataValue}
              onChange={(e) => setDraft({ ...draft, metadataValue: e.target.value })}
              placeholder="value"
              aria-label="Metadata value"
            />
          </Pair>

          <Button type="submit" variant="primary" size="sm">
            <Check />
            Apply
          </Button>
        </form>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function Pair({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-label-sm font-[510] text-fg-subtle">{label}</span>
      <div className="grid grid-cols-2 gap-2">{children}</div>
    </div>
  );
}

function toDraft(filters: PaymentFilters) {
  return {
    amountMin: filters.amountMin?.toString() ?? '',
    amountMax: filters.amountMax?.toString() ?? '',
    createdAfter: filters.createdAfter?.slice(0, 10) ?? '',
    createdBefore: filters.createdBefore?.slice(0, 10) ?? '',
    metadataKey: filters.metadataKey ?? '',
    metadataValue: filters.metadataValue ?? '',
  };
}

function wholeNumber(raw: string): number | undefined {
  const trimmed = raw.trim();
  if (trimmed.length === 0) return undefined;
  const value = Number(trimmed);
  return Number.isInteger(value) && value >= 0 ? value : undefined;
}

/**
 * @param endOfDay whether a bare date means the last instant of that day rather than the first.
 *
 * A `<input type="date">` gives `2026-08-10`, and `created_before=2026-08-10` would exclude every
 * payment made *on* the day the user picked — the classic off-by-one-day in a date filter, and the
 * kind that gets reported as "the dashboard is missing payments".
 */
function isoDate(raw: string, endOfDay = false): string | undefined {
  const trimmed = raw.trim();
  if (trimmed.length === 0) return undefined;
  const value = new Date(`${trimmed}T${endOfDay ? '23:59:59.999' : '00:00:00.000'}Z`);
  return Number.isNaN(value.getTime()) ? undefined : value.toISOString();
}
