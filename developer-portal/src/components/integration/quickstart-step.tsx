import { CircleCheck } from 'lucide-react';
import * as React from 'react';

import { CodeBlock, type CodeSample } from '@/components/ui/code-block';

/**
 * One numbered quickstart step: a short explanation, the exact command/code (with a copy
 * button and language tabs, via `CodeBlock`), and a "What should happen" line so the reader
 * can tell whether it worked. Server-rendered.
 */
export function QuickstartStep({
  n,
  title,
  children,
  samples,
  expect,
}: {
  n: number;
  title: string;
  children?: React.ReactNode;
  samples?: readonly CodeSample[];
  expect?: React.ReactNode;
}) {
  return (
    <li className="relative pl-10">
      <span className="absolute left-0 top-0 flex size-7 items-center justify-center rounded-full bg-surface-active text-label-sm font-[510] text-fg">
        {n}
      </span>
      <div className="pt-0.5">
        <h3 className="text-label font-[510] text-fg">{title}</h3>
        {children ? <div className="mt-1 text-label text-fg-subtle">{children}</div> : null}
        {samples && samples.length > 0 ? <CodeBlock className="mt-3" samples={samples} /> : null}
        {expect ? (
          <p className="mt-3 flex items-start gap-2 rounded-md bg-surface-inset px-3 py-2 text-label-sm text-fg-subtle ring-hairline">
            <CircleCheck aria-hidden className="mt-0.5 size-3.5 shrink-0 text-success" />
            <span>
              <span className="font-[510] text-fg">What should happen: </span>
              {expect}
            </span>
          </p>
        ) : null}
      </div>
    </li>
  );
}
