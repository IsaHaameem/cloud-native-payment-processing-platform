'use client';

import { FileCode2, ServerCog } from 'lucide-react';
import * as React from 'react';

import { CopyButton } from '@/components/ui/copy-button';
import { cn } from '@/lib/utils';

/**
 * "Where does this key go?" — a copyable environment-variable line, with the one instruction a
 * new integrator needs beside it.
 *
 * The value shown is a placeholder unless a real one is passed (a key is revealed exactly once,
 * at creation, and no screen re-shows it). The copy button copies the whole `NAME=value` line so
 * it can be pasted straight into a `.env` file.
 */
export function EnvVarField({
  name,
  value = 'your_key_here',
  file = '.env',
  className,
}: {
  name: string;
  value?: string;
  file?: string;
  className?: string | undefined;
}) {
  const line = `${name}=${value}`;
  return (
    <div className={cn('rounded-lg bg-surface-inset ring-hairline', className)}>
      <div className="flex items-center gap-1.5 border-b border-border-subtle px-3 py-2 text-label-sm text-fg-subtle">
        <ServerCog aria-hidden className="size-3.5" />
        <span className="font-[510]">Your server</span>
        <FileCode2 aria-hidden className="ml-2 size-3.5" />
        <span className="font-mono">{file}</span>
        <CopyButton value={line} className="ml-auto size-6" />
      </div>
      <pre className="overflow-x-auto px-3 py-2.5 font-mono text-[0.78rem] text-fg-muted">
        <code>{line}</code>
      </pre>
      <p className="border-t border-border-subtle px-3 py-2 text-label-sm text-fg-subtle">
        Keep this on your server. Never put a secret key in React, browser, or other client-side
        code, and never commit it — add <span className="font-mono">{name}=</span> (empty) to{' '}
        <span className="font-mono">.env.example</span> instead.
      </p>
    </div>
  );
}
