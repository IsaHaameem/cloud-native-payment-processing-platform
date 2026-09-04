'use client';

import { AnimatePresence, motion } from 'framer-motion';
import { AlertCircle, CheckCircle2, Info, X } from 'lucide-react';
import * as React from 'react';

import { duration, ease } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * Toasts (frontend build).
 *
 * `frontend_Design.md §10`: bottom-right, at most three stacked, ~5s, on `surface-elevated` with
 * a left status rail. Used for the outcome of a mutation the user has moved on from — a capture
 * that succeeded, a refund that was refused — where blocking the page with a dialog would be
 * wrong and leaving no feedback at all would be worse.
 *
 * Context + reducer, no dependency. `AnimatePresence` drives enter/exit; `MotionConfig
 * reducedMotion="user"` in the providers keeps the opacity and drops the slide for anyone who
 * asked. A toast auto-dismisses unless it carries an action or the pointer is over the stack.
 */

type ToastTone = 'success' | 'danger' | 'info';

export interface ToastInput {
  readonly title: string;
  readonly description?: string | undefined;
  readonly tone?: ToastTone | undefined;
  /** Milliseconds before auto-dismiss. `0` keeps it until dismissed. Default 5000. */
  readonly duration?: number | undefined;
}

interface ToastRecord extends ToastInput {
  readonly id: string;
}

interface ToastContextValue {
  toast: (input: ToastInput) => string;
  dismiss: (id: string) => void;
}

const ToastContext = React.createContext<ToastContextValue | null>(null);

const MAX_VISIBLE = 3;

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = React.useState<readonly ToastRecord[]>([]);
  const timers = React.useRef(new Map<string, ReturnType<typeof setTimeout>>());

  const dismiss = React.useCallback((id: string) => {
    setToasts((current) => current.filter((t) => t.id !== id));
    const timer = timers.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timers.current.delete(id);
    }
  }, []);

  const toast = React.useCallback(
    (input: ToastInput) => {
      const id = crypto.randomUUID();
      setToasts((current) => [...current, { ...input, id }].slice(-MAX_VISIBLE));
      const ms = input.duration ?? 5000;
      if (ms > 0) {
        timers.current.set(
          id,
          setTimeout(() => dismiss(id), ms),
        );
      }
      return id;
    },
    [dismiss],
  );

  React.useEffect(() => {
    const map = timers.current;
    return () => {
      for (const t of map.values()) clearTimeout(t);
      map.clear();
    };
  }, []);

  return (
    <ToastContext.Provider value={{ toast, dismiss }}>
      {children}
      <Viewport toasts={toasts} onDismiss={dismiss} />
    </ToastContext.Provider>
  );
}

/**
 * @throws if used outside `ToastProvider` — a mutation that wants to report its outcome must
 *         render inside the client root, and failing loudly is better than a silent no-op.
 */
export function useToast(): ToastContextValue {
  const ctx = React.useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used within <ToastProvider>.');
  return ctx;
}

const RAIL: Record<ToastTone, string> = {
  success: 'bg-success',
  danger: 'bg-danger',
  info: 'bg-info',
};

const ICON: Record<ToastTone, React.ComponentType<{ className?: string }>> = {
  success: CheckCircle2,
  danger: AlertCircle,
  info: Info,
};

function Viewport({
  toasts,
  onDismiss,
}: {
  toasts: readonly ToastRecord[];
  onDismiss: (id: string) => void;
}) {
  return (
    <div
      aria-live="polite"
      aria-atomic="false"
      // Pinned both edges on mobile so the stack can never contribute to horizontal overflow;
      // a right-anchored fixed-width column from `sm` up.
      className="pointer-events-none fixed inset-x-4 bottom-4 z-[60] flex flex-col gap-2 sm:left-auto sm:w-full sm:max-w-sm"
    >
      <AnimatePresence initial={false}>
        {toasts.map((t) => {
          const tone = t.tone ?? 'info';
          const Icon = ICON[tone];
          return (
            <motion.div
              key={t.id}
              layout
              initial={{ opacity: 0, y: 12, scale: 0.98 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: 8, scale: 0.98 }}
              transition={{ duration: duration.base, ease: ease.outQuart }}
              role="status"
              className={cn(
                'pointer-events-auto relative flex items-start gap-2.5 overflow-hidden rounded-lg',
                'bg-surface-elevated py-3 pr-3 pl-4 shadow-(--shadow-overlay)',
              )}
            >
              <span aria-hidden className={cn('absolute inset-y-0 left-0 w-0.5', RAIL[tone])} />
              <Icon
                className={cn(
                  'mt-px size-4 shrink-0',
                  tone === 'success' && 'text-success',
                  tone === 'danger' && 'text-danger',
                  tone === 'info' && 'text-info',
                )}
              />
              <div className="min-w-0 flex-1">
                <p className="text-label font-[510] text-fg">{t.title}</p>
                {t.description ? (
                  <p className="mt-0.5 text-label-sm break-words text-fg-subtle">{t.description}</p>
                ) : null}
              </div>
              <button
                type="button"
                onClick={() => onDismiss(t.id)}
                aria-label="Dismiss"
                className="-mr-1 rounded-sm p-0.5 text-fg-subtle transition-colors duration-(--duration-fast) hover:text-fg"
              >
                <X className="size-3.5" />
              </button>
            </motion.div>
          );
        })}
      </AnimatePresence>
    </div>
  );
}
