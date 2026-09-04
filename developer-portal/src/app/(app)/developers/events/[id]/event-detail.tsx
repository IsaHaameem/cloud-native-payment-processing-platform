'use client';

import { ArrowLeft } from 'lucide-react';
import Link from 'next/link';

import { ErrorState } from '@/components/patterns/error-state';
import { StatusPill } from '@/components/patterns/status-pill';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { CopyField } from '@/components/ui/copy-button';
import { JsonViewer } from '@/components/ui/json-viewer';
import { Skeleton } from '@/components/ui/skeleton';
import type { EventResponse } from '@/generated/models';
import { formatDateTime, truncateId } from '@/lib/format';
import { PlatformRequestError } from '@/lib/query/platform';
import { usePlatformObject } from '@/lib/query/use-platform';

export function EventDetail({ id }: { id: string }) {
  const query = usePlatformObject<EventResponse>('getEvent', id);

  if (query.isError) {
    const e = query.error;
    const notFound = e instanceof PlatformRequestError && e.status === 404;
    return (
      <div className="py-8">
        <h1 tabIndex={-1} className="sr-only">
          {notFound ? 'Event not found' : 'Could not load event'}
        </h1>
        <Back />
        <ErrorState
          title={notFound ? 'Event not found' : 'Could not load this event'}
          description={notFound ? 'No event with this id belongs to your account.' : e.message}
          {...(e instanceof PlatformRequestError && e.requestId ? { requestId: e.requestId } : {})}
          onRetry={notFound ? undefined : () => void query.refetch()}
        />
      </div>
    );
  }

  if (query.isPending || !query.data) {
    return (
      <div className="pb-10">
        <h1 tabIndex={-1} className="sr-only">
          Loading event
        </h1>
        <Back />
        <Skeleton className="mt-4 h-8 w-48" />
        <Skeleton className="mt-6 h-64 w-full rounded-lg" />
      </div>
    );
  }

  const ev = query.data;

  return (
    <div className="pb-10">
      <Back />
      <h1 tabIndex={-1} className="mt-3 font-mono text-title-2 font-[510] text-fg outline-none">
        {ev.type ?? 'Event'}
      </h1>
      <div className="mt-2 flex flex-wrap items-center gap-2.5">
        <StatusPill status={ev.type} family="event" dot label={ev.type} />
        {ev.mode ? <Badge tone={ev.mode === 'TEST' ? 'test' : 'outline'}>{ev.mode}</Badge> : null}
        {ev.created ? (
          <span className="text-label-sm text-fg-subtle">{formatDateTime(ev.created)}</span>
        ) : null}
      </div>
      {ev.id ? (
        <div className="mt-2">
          <CopyField value={ev.id} display={truncateId(ev.id)} />
        </div>
      ) : null}

      <Card className="mt-6">
        <CardContent className="pt-4">
          <h2 className="mb-3 text-label font-[510] text-fg">Payload</h2>
          <p className="mb-3 text-label-sm text-fg-subtle">
            Verbatim — byte-identical to what your webhook endpoint received for this event.
          </p>
          <JsonViewer data={ev.data ?? {}} />
        </CardContent>
      </Card>

      <Card className="mt-4">
        <CardContent className="pt-4">
          <Link href="/developers/webhooks" className="text-label text-accent-text hover:underline">
            View webhook deliveries for this event →
          </Link>
        </CardContent>
      </Card>
    </div>
  );
}

function Back() {
  return (
    <Link
      href="/developers/events"
      className="inline-flex items-center gap-1.5 text-label text-fg-subtle hover:text-fg"
    >
      <ArrowLeft className="size-3.5" /> Events
    </Link>
  );
}
