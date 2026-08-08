'use client';

import { Copy, ExternalLink, Inbox, RefreshCcw, Trash2 } from 'lucide-react';
import * as React from 'react';

import {
  DataTable,
  DataTableBody,
  DataTableCell,
  DataTableExpandableRow,
  DataTableHead,
} from '@/components/patterns/data-table';
import { FilterBar, type FilterOption } from '@/components/patterns/filter-bar';
import { MetricCard } from '@/components/patterns/metric-card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuLabel,
  ContextMenuSeparator,
  ContextMenuShortcut,
  ContextMenuTrigger,
} from '@/components/ui/context-menu';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { EmptyState } from '@/components/patterns/empty-state';
import { formatDateTime, formatMoney, truncateId } from '@/lib/format';

/**
 * The interactive half of the foundation page (M23.1 redesign).
 *
 * Split out as a client component so the page itself stays a server component and only the parts
 * that genuinely need state ship as JavaScript — the same leaf-not-trunk rule the header follows.
 *
 * Everything here is fixture data. No merchant data, no fetching, no business logic: this is the
 * design system demonstrating itself, which is what makes the interactions reviewable before
 * M23.6 has anything real to put through them.
 */

const FILTERS: readonly FilterOption[] = [
  { id: 'status:succeeded', label: 'Succeeded', group: 'Status' },
  { id: 'status:pending', label: 'Pending', group: 'Status' },
  { id: 'status:failed', label: 'Failed', group: 'Status' },
  { id: 'currency:eur', label: 'EUR', group: 'Currency' },
  { id: 'currency:usd', label: 'USD', group: 'Currency' },
  { id: 'mode:test', label: 'Test', group: 'Mode' },
];

const ROWS = [
  {
    id: 'pay_3fA9kQ2mZx7Lp0',
    amount: 4000,
    currency: 'EUR',
    status: 'succeeded',
    method: 'Visa · 4242',
  },
  {
    id: 'pay_8Lp0RtVn7Qc4Xa',
    amount: 129_999,
    currency: 'USD',
    status: 'pending',
    method: 'Mastercard · 5555',
  },
  {
    id: 'pay_2mZx7Lp0RtVn9K',
    amount: 1200,
    currency: 'JPY',
    status: 'failed',
    method: 'Amex · 0005',
  },
] as const;

const COLUMNS = ['Payment', 'Amount', 'Status', 'Created'] as const;

export function FoundationDemo() {
  const [selected, setSelected] = React.useState<readonly string[]>(['status:succeeded']);
  const [confirmOpen, setConfirmOpen] = React.useState(false);

  return (
    <div className="grid gap-5">
      <section>
        <SectionHeading
          title="Metrics"
          description="The number counts once, when the tile scrolls into view — the one slow animation in the system, because a figure that snaps reads as static and one that counts reads as measured."
        />
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <MetricCard
            label="Volume today"
            value={4_218_400}
            format={(v) => formatMoney(Math.round(v), 'EUR')}
            delta={0.124}
            series={[12, 18, 14, 22, 26, 21, 30, 34]}
          />
          <MetricCard
            label="Payments"
            value={1284}
            format={(v) => Math.round(v).toLocaleString('en-US')}
            delta={0.052}
            series={[8, 9, 11, 10, 14, 13, 17, 19]}
          />
          <MetricCard
            label="Success rate"
            value={98.4}
            format={(v) => `${v.toFixed(1)}%`}
            delta={-0.004}
            invertDelta
            series={[99, 98.8, 98.9, 98.6, 98.5, 98.4, 98.4, 98.4]}
            hint="Last 24 hours"
          />
          <MetricCard
            label="Failure rate"
            value={1.6}
            format={(v) => `${v.toFixed(1)}%`}
            delta={0.004}
            invertDelta
            series={[1, 1.2, 1.1, 1.4, 1.5, 1.6, 1.6, 1.6]}
            hint="Rising is bad here"
          />
        </div>
      </section>

      <section>
        <SectionHeading
          title="Filters"
          description="Chips push their neighbours aside instead of making the row jump — which keeps “Clear all” from teleporting under a cursor aimed elsewhere."
        />
        <FilterBar options={FILTERS} selected={selected} onChange={setSelected} />
      </section>

      <section>
        <SectionHeading
          title="Table"
          description="Rows expand in place rather than navigating away, and right-clicking one opens its actions. Try both — and Tab to the disclosure control, which is a real button."
        />
        <DataTable>
          <DataTableHead columns={['', ...COLUMNS]} />
          <DataTableBody>
            {ROWS.map((row) => (
              <ContextMenu key={row.id}>
                <ContextMenuTrigger asChild>
                  <DataTableExpandableRow
                    label={`payment ${truncateId(row.id)}`}
                    columnCount={COLUMNS.length}
                    summary={
                      <>
                        <DataTableCell className="tabular font-mono text-fg">
                          {truncateId(row.id)}
                        </DataTableCell>
                        <DataTableCell className="tabular">
                          {formatMoney(row.amount, row.currency)}
                        </DataTableCell>
                        <DataTableCell>
                          <Badge
                            dot
                            tone={
                              row.status === 'succeeded'
                                ? 'success'
                                : row.status === 'pending'
                                  ? 'warning'
                                  : 'danger'
                            }
                          >
                            {row.status}
                          </Badge>
                        </DataTableCell>
                        <DataTableCell className="tabular text-fg-subtle">
                          {formatDateTime('2026-08-02T09:15:00Z')}
                        </DataTableCell>
                      </>
                    }
                    detail={
                      <dl className="grid gap-x-10 gap-y-1.5 sm:grid-cols-2">
                        <DetailRow label="Payment method" value={row.method} />
                        <DetailRow label="Full id" value={row.id} mono />
                        <DetailRow label="Captured" value={formatMoney(row.amount, row.currency)} />
                        <DetailRow label="Refunded" value={formatMoney(0, row.currency)} />
                      </dl>
                    }
                  />
                </ContextMenuTrigger>
                <ContextMenuContent>
                  <ContextMenuLabel>{truncateId(row.id)}</ContextMenuLabel>
                  <ContextMenuItem>
                    <Copy /> Copy id <ContextMenuShortcut>⌘C</ContextMenuShortcut>
                  </ContextMenuItem>
                  <ContextMenuItem>
                    <ExternalLink /> Open detail <ContextMenuShortcut>↵</ContextMenuShortcut>
                  </ContextMenuItem>
                  <ContextMenuSeparator />
                  <ContextMenuItem destructive onSelect={() => setConfirmOpen(true)}>
                    <RefreshCcw /> Refund…
                  </ContextMenuItem>
                </ContextMenuContent>
              </ContextMenu>
            ))}
          </DataTableBody>
        </DataTable>
      </section>

      <section>
        <SectionHeading
          title="Dialogs and empty states"
          description="The scrim blurs rather than only dimming, so the page behind reads as behind. The empty state's ring expands once — never on a loop."
        />
        <div className="grid gap-4 lg:grid-cols-2">
          <EmptyState
            icon={<Inbox className="size-4" />}
            title="No payments yet"
            description="When your first payment is created it appears here, with its full lifecycle timeline."
            action={<Button variant="primary">Read the quickstart</Button>}
          />

          <div className="flex items-center justify-center rounded-lg bg-surface px-6 py-16 ring-hairline">
            <Button variant="secondary" onClick={() => setConfirmOpen(true)}>
              <Trash2 />
              Open a destructive dialog
            </Button>
          </div>
        </div>
      </section>

      <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <DialogContent open={confirmOpen}>
          <DialogHeader>
            <DialogTitle>Refund €40.00?</DialogTitle>
            <DialogDescription>
              This names the mode explicitly, because a confirmation that does not is how a live
              refund gets issued from a test-mode habit. Nothing here is wired — M23.7 does that.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setConfirmOpen(false)}>
              Cancel
            </Button>
            <Button variant="danger" onClick={() => setConfirmOpen(false)}>
              Refund test payment
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function SectionHeading({ title, description }: { title: string; description: string }) {
  return (
    <div className="mb-3">
      <h2 className="text-title-2 font-[510] tracking-[-0.165px] text-fg">{title}</h2>
      <p className="mt-0.5 max-w-3xl text-body text-fg-subtle">{description}</p>
    </div>
  );
}

function DetailRow({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-4 text-label">
      <dt className="text-fg-subtle">{label}</dt>
      <dd className={mono ? 'tabular font-mono text-fg' : 'text-fg'}>{value}</dd>
    </div>
  );
}
