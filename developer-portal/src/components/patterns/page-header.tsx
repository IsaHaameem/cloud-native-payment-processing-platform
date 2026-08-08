import * as React from 'react';

import { cn } from '@/lib/utils';

/**
 * The heading block every page opens with (M23.1, redesigned to the Linear system).
 *
 * Exists so page titles cannot drift apart in size, weight or spacing as screens are added one
 * milestone at a time — the most visible way a dashboard stops looking designed.
 *
 * The title is the extracted `title-2` role (18px / 510 / -0.165px), not a 40px `title-1`. The
 * reference reserves the large display sizes for its marketing surface; inside the product,
 * headings are quiet and the density does the work. The `<h1>` lives here rather than in each
 * page for a second reason: M23.3's route-change focus management moves focus to the page
 * heading, and that only works if every page has exactly one in a predictable place.
 */
export function PageHeader({
  title,
  description,
  actions,
  className,
}: {
  title: string;
  description?: string | undefined;
  actions?: React.ReactNode | undefined;
  className?: string | undefined;
}) {
  return (
    <div className={cn('flex flex-wrap items-start justify-between gap-4 pb-8', className)}>
      <div className="space-y-1">
        <h1
          tabIndex={-1}
          className="text-title-2 font-[510] tracking-[-0.165px] text-fg outline-none"
        >
          {title}
        </h1>
        {description ? <p className="max-w-2xl text-body text-fg-subtle">{description}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </div>
  );
}
