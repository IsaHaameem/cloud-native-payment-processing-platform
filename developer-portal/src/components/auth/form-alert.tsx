import { AlertCircle, CheckCircle2 } from 'lucide-react';

import { cn } from '@/lib/utils';

/**
 * The message region on an entry page (M23.2a).
 *
 * ── It is always in the tree ──────────────────────────────────────────────────────────
 *
 * The container renders whether or not there is a message, and only its *contents* change. A
 * live region inserted at the moment it gains content is announced inconsistently across screen
 * readers — some observe the insertion, some only observe mutations to a region they were
 * already watching. One that is present from first paint is announced reliably. This was the
 * arrangement the M23.2 login form already used; it is lifted here so signup cannot get it
 * subtly wrong.
 *
 * `aria-live="polite"` rather than `assertive`: a failed sign-in is not an emergency, and
 * assertive interrupts whatever the user is currently reading — including the field label they
 * are in the middle of hearing.
 */
export function FormAlert({
  tone = 'danger',
  children,
}: {
  tone?: 'danger' | 'success' | undefined;
  children?: React.ReactNode | undefined;
}) {
  const Icon = tone === 'success' ? CheckCircle2 : AlertCircle;

  return (
    <div role="alert" aria-live="polite" className="min-h-0">
      {children ? (
        <p
          className={cn(
            'flex items-start gap-1.5 text-label',
            tone === 'success' ? 'text-success' : 'text-danger',
          )}
        >
          <Icon aria-hidden className="mt-px size-3.5 shrink-0" />
          <span>{children}</span>
        </p>
      ) : null}
    </div>
  );
}
