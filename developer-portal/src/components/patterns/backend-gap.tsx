import { PlugZap } from 'lucide-react';

import { cn } from '@/lib/utils';

/**
 * The project's one treatment for a screen the backend does not fully support yet (frontend
 * build).
 *
 * `frontend_Design.md §38` catalogues the gaps as G-1…G-11: a real domain model with no HTTP
 * API, an aggregate the service measures but does not expose, a config that is read from a file
 * at boot. The design for each of these screens is complete and worth showing — but a merchant
 * must never be shown a fake successful operation, so the page renders the full layout with this
 * notice standing in for the data it cannot load.
 *
 * It is deliberately **not** an error: amber, not red, and worded as scheduling rather than
 * failure. It carries the gap's own id so the limitation is traceable to the spec.
 */
export function BackendGapNotice({
  id,
  title,
  children,
  className,
}: {
  /** The G-number from `frontend_Design.md §38`, e.g. "G-2". */
  id: string;
  title: string;
  children: React.ReactNode;
  className?: string | undefined;
}) {
  return (
    <div
      role="note"
      className={cn(
        'flex flex-col gap-2 rounded-lg border border-mode-test-border bg-mode-test-surface p-4',
        'sm:flex-row sm:gap-3 sm:p-5',
        className,
      )}
    >
      <PlugZap aria-hidden className="mt-0.5 size-4 shrink-0 text-mode-test" />
      <div className="min-w-0 space-y-1">
        <p className="flex flex-wrap items-center gap-2 text-label font-[510] text-fg">
          {title}
          <span className="rounded bg-mode-test-surface px-1.5 font-mono text-caption font-[510] tracking-[0.06em] text-mode-test ring-1 ring-mode-test-border ring-inset">
            BACKEND GAP · {id}
          </span>
        </p>
        <div className="text-label text-pretty text-fg-subtle [&_code]:font-mono [&_code]:text-fg-muted">
          {children}
        </div>
      </div>
    </div>
  );
}
