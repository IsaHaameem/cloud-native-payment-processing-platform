'use client';

import { Check, Copy } from 'lucide-react';
import * as React from 'react';

import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';

/**
 * Copy-to-clipboard, one place (frontend build).
 *
 * Every id, key prefix, secret and code sample in the product has a copy affordance, and they
 * all confirm the same way: the icon swaps to a check for ~1.6s and the label, if there is one,
 * follows. `navigator.clipboard` can reject (permissions, insecure context), so the write is
 * guarded and a failure simply does not show the confirmation rather than throwing.
 */
export function useCopy(): {
  copied: boolean;
  copy: (text: string) => void;
} {
  const [copied, setCopied] = React.useState(false);
  const timer = React.useRef<ReturnType<typeof setTimeout>>(undefined);

  React.useEffect(() => () => clearTimeout(timer.current), []);

  const copy = React.useCallback((text: string) => {
    void navigator.clipboard
      ?.writeText(text)
      .then(() => {
        setCopied(true);
        clearTimeout(timer.current);
        timer.current = setTimeout(() => setCopied(false), 1600);
      })
      .catch(() => setCopied(false));
  }, []);

  return { copied, copy };
}

export function CopyButton({
  value,
  label,
  size = 'sm',
  variant = 'ghost',
  className,
}: {
  value: string;
  label?: string | undefined;
  size?: 'sm' | 'md' | 'icon';
  variant?: 'ghost' | 'secondary';
  className?: string | undefined;
}) {
  const { copied, copy } = useCopy();

  return (
    <Button
      type="button"
      size={label ? size : 'icon'}
      variant={variant}
      onClick={() => copy(value)}
      aria-label={copied ? 'Copied' : `Copy ${label ?? value}`}
      className={className}
    >
      {copied ? <Check className="text-success" /> : <Copy />}
      {label ? <span>{copied ? 'Copied' : label}</span> : null}
    </Button>
  );
}

/**
 * A mono value beside a copy button — the shape every identifier takes on a detail page.
 * `truncate` is opt-in via `title`: pass the full value and it stays selectable and hoverable.
 */
export function CopyField({
  value,
  display,
  className,
}: {
  value: string;
  display?: string | undefined;
  className?: string | undefined;
}) {
  return (
    <span
      className={cn(
        'inline-flex max-w-full items-center gap-1 rounded-md bg-surface-inset px-2 py-1 ring-hairline',
        className,
      )}
    >
      <span title={value} className="truncate font-mono text-label-sm text-fg-muted select-all">
        {display ?? value}
      </span>
      <CopyButton value={value} className="-mr-1 size-6 shrink-0" />
    </span>
  );
}
