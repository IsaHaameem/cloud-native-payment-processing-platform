'use client';

import { motion } from 'framer-motion';
import { AlertTriangle, RotateCcw } from 'lucide-react';
import * as React from 'react';

import { Button } from '@/components/ui/button';
import { cardVariants } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * What a surface shows when a call failed (M23.1, redesigned to the Linear system).
 *
 * `requestId` is rendered whenever there is one, and that is the point of the component. The
 * platform's error contract tells a merchant to quote it in a support request, and
 * `docs/ERRORS.md` says so — an error screen that swallows it makes the platform's own advice
 * impossible to follow, which is a worse failure than the error itself.
 *
 * The identifiers sit in a mono block on the inset surface, which is where the reference puts
 * code: `Berkeley Mono` is licensed and unavailable, so the stack falls through to the
 * platform's own monospace, exactly as the reference's own fallback list does.
 */
export function ErrorState({
  title = 'Something went wrong',
  description,
  code,
  requestId,
  onRetry,
  className,
}: {
  /*
   * `T | undefined` rather than `T?` on every optional prop, throughout the portal. Under
   * `exactOptionalPropertyTypes` those are different types, and the difference decides whether
   * `requestId={error.digest}` compiles — see D194.
   */
  title?: string | undefined;
  description?: string | undefined;
  code?: string | undefined;
  requestId?: string | undefined;
  onRetry?: (() => void) | undefined;
  className?: string | undefined;
}) {
  return (
    <motion.div
      role="alert"
      variants={cardVariants}
      initial="hidden"
      animate="visible"
      className={cn(
        'gpu flex flex-col items-center justify-center rounded-lg bg-surface px-6 py-16',
        'text-center ring-hairline',
        className,
      )}
    >
      <div
        aria-hidden
        className="mb-4 flex size-9 items-center justify-center rounded-md bg-danger-surface text-danger"
      >
        <AlertTriangle className="size-4" />
      </div>
      <p className="text-body-lg font-[510] text-fg">{title}</p>
      {description ? (
        <p className="mt-1.5 max-w-md text-body text-balance text-fg-subtle">{description}</p>
      ) : null}

      {code || requestId ? (
        <dl className="mt-5 grid gap-1 rounded-md bg-surface-inset px-3 py-2 text-left ring-hairline">
          {code ? (
            <div className="flex items-baseline gap-3 text-label-sm">
              <dt className="text-fg-subtle">Code</dt>
              <dd className="font-mono text-fg-subtle">{code}</dd>
            </div>
          ) : null}
          {requestId ? (
            <div className="flex items-baseline gap-3 text-label-sm">
              <dt className="text-fg-subtle">Request</dt>
              <dd className="tabular font-mono text-fg-subtle select-all">{requestId}</dd>
            </div>
          ) : null}
        </dl>
      ) : null}

      {onRetry ? (
        <Button variant="secondary" size="md" className="mt-6" onClick={onRetry}>
          <RotateCcw />
          Try again
        </Button>
      ) : null}
    </motion.div>
  );
}
