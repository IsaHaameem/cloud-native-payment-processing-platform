'use client';

import * as React from 'react';

import { CopyButton } from '@/components/ui/copy-button';
import { cn } from '@/lib/utils';

export interface CodeSample {
  /** Tab label, e.g. "Node.js", "Python", "cURL". */
  readonly label: string;
  readonly code: string;
  /** Shown at the right of the strip, e.g. "payment.ts". */
  readonly filename?: string;
}

/**
 * A code sample with an optional language switcher (frontend build).
 *
 * One component for the marketing pages, the docs and the onboarding flow, so a snippet reads
 * the same everywhere: `surface-inset` well, a strip carrying the language tabs and a copy
 * button, and a `<pre>` that scrolls inside itself rather than widening the page. No syntax
 * highlighter — the reference renders code in a single foreground colour, and a highlighter is
 * a dependency and a hydration surface for a cosmetic gain.
 *
 * The tab state is local and defaults to the first sample. `prefers-reduced-motion` is a
 * non-issue here: switching tabs is an instant content swap, not an animation.
 */
export function CodeBlock({
  samples,
  className,
  caption,
}: {
  samples: readonly CodeSample[];
  className?: string | undefined;
  /** A line under the block, e.g. an "install commands are illustrative" note. */
  caption?: React.ReactNode;
}) {
  const [active, setActive] = React.useState(0);
  const current = samples[active] ?? samples[0];
  if (!current) return null;

  const multi = samples.length > 1;

  return (
    <div
      className={cn(
        // `min-w-0` so a CodeBlock used as a grid or flex child can shrink below its content
        // width and let the <pre> scroll internally rather than pushing the page.
        'min-w-0 overflow-hidden rounded-lg bg-surface-inset ring-hairline',
        className,
      )}
    >
      <div className="flex items-center gap-1 border-b border-border-subtle p-1.5">
        {multi ? (
          <div role="tablist" aria-label="Language" className="flex items-center gap-1">
            {samples.map((sample, index) => {
              const selected = index === active;
              return (
                <button
                  key={sample.label}
                  type="button"
                  role="tab"
                  aria-selected={selected}
                  onClick={() => setActive(index)}
                  className={cn(
                    'h-7 rounded-sm px-2.5 text-label-sm font-[510] transition-colors duration-(--duration-fast)',
                    selected ? 'bg-surface-active text-fg' : 'text-fg-subtle hover:text-fg',
                  )}
                >
                  {sample.label}
                </button>
              );
            })}
          </div>
        ) : (
          <span className="px-1.5 font-mono text-label-sm text-fg-subtle">{current.label}</span>
        )}

        <div className="ml-auto flex items-center gap-2 pr-0.5">
          {current.filename ? (
            <span className="hidden font-mono text-label-sm text-fg-subtle sm:inline">
              {current.filename}
            </span>
          ) : null}
          <CopyButton value={current.code} className="size-7" />
        </div>
      </div>

      <pre className="w-full min-w-0 max-w-full overflow-x-auto p-4 font-mono text-[0.78rem] leading-[1.6] text-fg-muted">
        <code>{current.code}</code>
      </pre>

      {caption ? (
        <p className="border-t border-border-subtle px-4 py-2.5 text-label-sm text-fg-subtle">
          {caption}
        </p>
      ) : null}
    </div>
  );
}
