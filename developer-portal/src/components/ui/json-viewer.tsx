'use client';

import * as React from 'react';

import { CopyButton } from '@/components/ui/copy-button';
import { cn } from '@/lib/utils';

/**
 * A read-only JSON view (frontend build).
 *
 * `frontend_Design.md §10`: mono `label-sm` on the inset surface, keys `fg-muted`, strings
 * `success`, numbers `info`, null/bool `fg-subtle`, and — the load-bearing rule — a redacted
 * value renders as a `[REDACTED]` chip, never as text and never with a reveal affordance,
 * because the value was redacted before it was ever sent to the browser (§21.4).
 *
 * A hand-written pretty-printer rather than a library: the payloads here are small event and
 * action objects, the output is deterministic, and it keeps the CSP free of another script
 * origin. Depth is capped so a pathological object cannot lock the tab.
 */

const REDACTED_MARKERS = new Set(['[REDACTED]', '***', '[redacted]', 'REDACTED']);

function isRedacted(value: unknown): boolean {
  return typeof value === 'string' && REDACTED_MARKERS.has(value.trim());
}

function Line({ children, indent }: { children: React.ReactNode; indent: number }) {
  return (
    <div style={{ paddingLeft: `${indent * 1}rem` }} className="whitespace-pre-wrap">
      {children}
    </div>
  );
}

function renderValue(value: unknown, indent: number, keyPrefix: string): React.ReactNode {
  if (isRedacted(value)) {
    return (
      <span className="rounded bg-danger-surface px-1 font-[510] text-danger">[REDACTED]</span>
    );
  }
  if (value === null) return <span className="text-fg-subtle">null</span>;
  if (typeof value === 'boolean' || typeof value === 'number') {
    return <span className="text-info">{String(value)}</span>;
  }
  if (typeof value === 'string') {
    return <span className="text-success break-all">&quot;{value}&quot;</span>;
  }
  if (Array.isArray(value)) {
    if (value.length === 0) return <span className="text-fg-subtle">[]</span>;
    return (
      <>
        <span className="text-fg-subtle">[</span>
        {value.map((item, index) => (
          <Line key={`${keyPrefix}-${index}`} indent={indent + 1}>
            {renderValue(item, indent + 1, `${keyPrefix}-${index}`)}
            {index < value.length - 1 ? <span className="text-fg-subtle">,</span> : null}
          </Line>
        ))}
        <Line indent={indent}>
          <span className="text-fg-subtle">]</span>
        </Line>
      </>
    );
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length === 0) return <span className="text-fg-subtle">{'{}'}</span>;
    return (
      <>
        <span className="text-fg-subtle">{'{'}</span>
        {entries.map(([key, val], index) => (
          <Line key={`${keyPrefix}-${key}`} indent={indent + 1}>
            <span className="text-fg-muted">&quot;{key}&quot;</span>
            <span className="text-fg-subtle">: </span>
            {renderValue(val, indent + 1, `${keyPrefix}-${key}`)}
            {index < entries.length - 1 ? <span className="text-fg-subtle">,</span> : null}
          </Line>
        ))}
        <Line indent={indent}>
          <span className="text-fg-subtle">{'}'}</span>
        </Line>
      </>
    );
  }
  return <span className="text-fg-subtle">{String(value)}</span>;
}

export function JsonViewer({
  data,
  className,
  copyable = true,
}: {
  data: unknown;
  className?: string | undefined;
  copyable?: boolean | undefined;
}) {
  const raw = React.useMemo(() => {
    try {
      return JSON.stringify(data, null, 2);
    } catch {
      return String(data);
    }
  }, [data]);

  return (
    <div
      className={cn(
        'relative min-w-0 overflow-hidden rounded-lg bg-surface-inset ring-hairline',
        className,
      )}
    >
      {copyable ? (
        <CopyButton value={raw} className="absolute top-1.5 right-1.5 z-10 size-7" />
      ) : null}
      <div className="overflow-x-auto p-4 font-mono text-label-sm leading-[1.65]">
        {renderValue(data, 0, 'root')}
      </div>
    </div>
  );
}
