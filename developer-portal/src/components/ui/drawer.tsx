'use client';

import * as DialogPrimitive from '@radix-ui/react-dialog';
import { AnimatePresence, motion } from 'framer-motion';
import { X } from 'lucide-react';
import * as React from 'react';

import { duration, ease } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * A right-hand panel for detail-without-navigation (frontend build).
 *
 * `frontend_Design.md §10` specifies a 480–720px right sheet for "event payload, delivery
 * attempt" — the cases where you want one more object without losing the list you are scanning.
 * Distinct from `sheet.tsx`, which is the ~288px mobile navigation drawer; this one is wider,
 * has a titled header, and its body scrolls.
 *
 * Built on Radix's dialog for the focus trap, the inert background, `Escape` and focus return —
 * every one of those a way to strand a keyboard user, and none visible in a screenshot. The
 * caller owns `open` so the exit animation can outlive the unmount (`AnimatePresence`).
 */
export function Drawer({
  open,
  onOpenChange,
  title,
  description,
  children,
  footer,
  className,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: React.ReactNode;
  description?: React.ReactNode;
  children: React.ReactNode;
  footer?: React.ReactNode;
  className?: string | undefined;
}) {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <AnimatePresence>
        {open ? (
          <DialogPrimitive.Portal forceMount>
            <DialogPrimitive.Overlay asChild forceMount>
              <motion.div
                className="fixed inset-0 z-50 bg-black/50 backdrop-blur-[4px]"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                transition={{ duration: duration.fast }}
              />
            </DialogPrimitive.Overlay>

            <DialogPrimitive.Content asChild forceMount>
              <motion.div
                className={cn(
                  'gpu fixed inset-y-0 right-0 z-50 flex w-full max-w-[min(92vw,560px)] flex-col',
                  'bg-surface-elevated shadow-(--shadow-overlay)',
                  className,
                )}
                initial={{ x: '100%' }}
                animate={{ x: 0 }}
                exit={{ x: '100%' }}
                transition={{ duration: duration.base, ease: ease.outQuart }}
              >
                <div className="flex items-start justify-between gap-3 border-b border-border-subtle px-5 py-4">
                  <div className="min-w-0">
                    <DialogPrimitive.Title className="text-title-2 font-[510] tracking-[-0.165px] text-fg">
                      {title}
                    </DialogPrimitive.Title>
                    {description ? (
                      <DialogPrimitive.Description className="mt-0.5 text-label text-fg-subtle">
                        {description}
                      </DialogPrimitive.Description>
                    ) : (
                      <DialogPrimitive.Description className="sr-only">
                        Detail panel
                      </DialogPrimitive.Description>
                    )}
                  </div>
                  <DialogPrimitive.Close
                    className="-mr-1 rounded-sm p-1 text-fg-subtle transition-colors duration-(--duration-fast) hover:text-fg"
                    aria-label="Close"
                  >
                    <X className="size-4" />
                  </DialogPrimitive.Close>
                </div>

                <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">{children}</div>

                {footer ? (
                  <div className="flex items-center justify-end gap-2 border-t border-border-subtle px-5 py-3">
                    {footer}
                  </div>
                ) : null}
              </motion.div>
            </DialogPrimitive.Content>
          </DialogPrimitive.Portal>
        ) : null}
      </AnimatePresence>
    </DialogPrimitive.Root>
  );
}
