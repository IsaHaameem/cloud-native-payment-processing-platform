import { Info } from 'lucide-react';

import { cn } from '@/lib/utils';

/**
 * A small inline marker for a panel showing illustrative rather than live figures (frontend
 * build).
 *
 * Used sparingly — only where a screen is intentionally demonstrating a product concept the
 * backend cannot yet feed (a metric the service measures as a Prometheus counter but exposes no
 * JSON for, per `frontend_Design.md §17`). It is `info`-toned, never `success`, so it can never
 * be mistaken for a confirmed number. Anywhere real data exists, this must not appear.
 */
export function SampleDataNotice({
  children = 'Illustrative figures — no aggregate endpoint yet.',
  className,
}: {
  children?: React.ReactNode;
  className?: string | undefined;
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full bg-info-surface px-2 py-0.5 text-label-sm font-[510] text-info',
        className,
      )}
    >
      <Info aria-hidden className="size-3" />
      {children}
    </span>
  );
}
