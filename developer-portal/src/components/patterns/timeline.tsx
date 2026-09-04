import * as React from 'react';

import { cn } from '@/lib/utils';

export type TimelineTone = 'success' | 'info' | 'warning' | 'danger' | 'neutral' | 'accent';

const DOT: Record<TimelineTone, string> = {
  success: 'bg-success',
  info: 'bg-info',
  warning: 'bg-warning',
  danger: 'bg-danger',
  neutral: 'bg-fg-faint',
  accent: 'bg-accent',
};

export interface TimelineNode {
  readonly title: React.ReactNode;
  readonly meta?: React.ReactNode;
  readonly body?: React.ReactNode;
  readonly tone?: TimelineTone;
}

/**
 * The vertical trace (frontend build).
 *
 * `frontend_Design.md §10` names this "the primary device for payment lifecycle and agent
 * action trace": a rail with 8px status dots, a `label` title and a `caption` timestamp. It is
 * presentation only — every node is passed in already resolved, because the two screens that use
 * it (payment lifecycle from `/v1/events`, agent trace from the action log) assemble their nodes
 * from very different shapes.
 *
 * The rail is a `::before` on each item rather than one tall element, so a node can be any
 * height and the line still meets the next dot.
 */
export function Timeline({
  nodes,
  className,
}: {
  nodes: readonly TimelineNode[];
  className?: string | undefined;
}) {
  return (
    <ol className={cn('relative', className)}>
      {nodes.map((node, index) => {
        const last = index === nodes.length - 1;
        return (
          <li key={index} className="relative flex gap-3 pb-5 last:pb-0">
            {!last ? (
              <span aria-hidden className="absolute top-2.5 bottom-0 left-[3.5px] w-px bg-border" />
            ) : null}
            <span
              aria-hidden
              className={cn(
                'relative mt-1.5 size-2 shrink-0 rounded-full ring-4 ring-surface',
                DOT[node.tone ?? 'neutral'],
              )}
            />
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-baseline justify-between gap-x-3">
                <p className="text-label font-[510] text-fg">{node.title}</p>
                {node.meta ? (
                  <span className="text-caption text-fg-subtle">{node.meta}</span>
                ) : null}
              </div>
              {node.body ? (
                <div className="mt-1 text-label-sm text-fg-subtle">{node.body}</div>
              ) : null}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
