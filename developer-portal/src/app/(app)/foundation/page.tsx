import type { Metadata } from 'next';

import { FoundationDemo } from '@/app/(app)/foundation/demo';
import { ErrorState } from '@/components/patterns/error-state';
import { PageHeader } from '@/components/patterns/page-header';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { formatDateTime, formatMoney, truncateId } from '@/lib/format';

export const metadata: Metadata = { title: 'Foundation' };

/**
 * The design system, rendered (M23.1, redesigned to the Linear system).
 *
 * This is the milestone's own deliverable made reviewable. A design system that exists only as
 * files is a claim; one page that renders every primitive in both themes is the evidence, and it
 * is where the next milestone's author checks whether a component already exists before writing
 * a second one.
 *
 * It is not a dashboard. No merchant data, no widgets, no business logic — those belong to
 * M23.4 onwards. The table below is the *shell* M23.6 fills, shown here so the row rhythm and
 * the stagger are reviewable before there is anything to put in it.
 */
export default function FoundationPage() {
  return (
    <div>
      <PageHeader
        title="Foundation"
        description="Every primitive the portal is built from, in the current theme. Switch themes from the header — both are designed, neither is an inversion of the other."
        actions={<Button variant="primary">Primary action</Button>}
      />

      <FoundationDemo />

      <div className="mt-5 grid gap-5">
        <Section
          title="Buttons"
          description="Five variants, four sizes. The accent belongs to one button per screen."
        >
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="primary">Primary</Button>
            <Button variant="secondary">Secondary</Button>
            <Button variant="ghost">Ghost</Button>
            <Button variant="danger">Danger</Button>
            <Button variant="link">Link</Button>
            <Button variant="secondary" disabled>
              Disabled
            </Button>
          </div>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <Button size="sm" variant="secondary">
              Small
            </Button>
            <Button size="md" variant="secondary">
              Medium
            </Button>
            <Button size="lg" variant="secondary">
              Large
            </Button>
          </div>
        </Section>

        <Section
          title="Status"
          description="Pills, per the reference. Every tone pairs a colour with a label, and a dot adds a second channel where the label alone is ambiguous."
        >
          <div className="flex flex-wrap items-center gap-2">
            <Badge tone="success" dot>
              Succeeded
            </Badge>
            <Badge tone="warning" dot>
              Pending
            </Badge>
            <Badge tone="danger" dot>
              Failed
            </Badge>
            <Badge tone="info" dot>
              Refunded
            </Badge>
            <Badge tone="neutral">Created</Badge>
            <Badge tone="accent">New</Badge>
            <Badge tone="outline">Draft</Badge>
            <Badge tone="test" dot>
              Test mode
            </Badge>
          </div>
        </Section>

        <Section
          title="Type scale"
          description="The extracted roles: 64 · 40 · 18 · 16 · 15 · 13 · 12 · 10, Inter Variable at weight 510 with negative tracking at display sizes."
        >
          <div className="space-y-3">
            <p className="text-title-1 font-[510] tracking-[-0.88px] text-fg">Title 1 · 40px</p>
            <p className="text-title-2 font-[510] tracking-[-0.165px] text-fg">Title 2 · 18px</p>
            <p className="text-body-lg text-fg">Body base · 16px</p>
            <p className="text-body text-fg-muted">
              Body regular · 15px — the portal&rsquo;s default
            </p>
            <p className="text-label font-[510] text-fg-muted">Label medium · 13px / 510</p>
            <p className="text-label-sm font-[510] text-fg-subtle">Label small · 12px / 510</p>
            <p className="text-caption font-[510] tracking-[0.04em] text-fg-subtle uppercase">
              Caption · 10px / 510
            </p>
            <p className="font-mono text-body text-fg-subtle">Code inline · monospace</p>
          </div>
        </Section>

        <Section
          title="Numbers"
          description="Money is integer minor units through one function; ids and timestamps are tabular so a column lines up."
        >
          <dl className="grid gap-x-10 gap-y-1 sm:grid-cols-2">
            <Row label="EUR 4000 minor" value={formatMoney(4000, 'EUR')} />
            <Row label="JPY 1200 minor" value={formatMoney(1200, 'JPY')} />
            <Row label="USD 129999 minor" value={formatMoney(129_999, 'USD')} />
            <Row label="BHD 12345 minor" value={formatMoney(12_345, 'BHD')} />
            <Row label="Object id" value={truncateId('pay_3fA9kQ2mZx7Lp0RtVn')} />
            <Row label="Timestamp" value={formatDateTime('2026-08-02T09:15:00Z')} />
          </dl>
        </Section>

        <Section
          title="Fields"
          description="One height and one edge treatment, matched to the medium button."
        >
          <div className="flex max-w-md flex-col gap-2">
            <Input placeholder="pay_3fA9kQ…" aria-label="Search by object id" />
            <div className="flex gap-2">
              <Input placeholder="Amount" aria-label="Amount" />
              <Button variant="secondary">Apply</Button>
            </div>
          </div>
        </Section>

        <Section
          title="Loading"
          description="A shimmer, not a pulse — a sweep reads as loading, a fade reads as broken."
        >
          <div className="grid gap-3 sm:grid-cols-3">
            <Skeleton className="h-20 rounded-lg" />
            <Skeleton className="h-20 rounded-lg" />
            <Skeleton className="h-20 rounded-lg" />
          </div>
        </Section>

        <ErrorState
          title="This page could not be loaded"
          description="An error surface always shows the request id, because that is what a support conversation starts with."
          code="RATE_LIMIT_EXCEEDED"
          requestId="req_8Kd2mQ1xLp"
        />
      </div>
    </div>
  );
}

function Section({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children: React.ReactNode;
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <Separator />
      <CardContent className="pt-5">{children}</CardContent>
    </Card>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-4 border-b border-border-subtle py-2">
      <dt className="text-body text-fg-subtle">{label}</dt>
      <dd className="tabular font-mono text-label text-fg">{value}</dd>
    </div>
  );
}
