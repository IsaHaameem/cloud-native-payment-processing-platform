'use client';

import * as DialogPrimitive from '@radix-ui/react-dialog';
import { AnimatePresence, motion } from 'framer-motion';
import { X } from 'lucide-react';
import * as React from 'react';

import { dialogVariants, scrimVariants } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * A floating dialog (M23.1 redesign).
 *
 * On Radix for the behaviour a hand-rolled overlay always misses: focus trap, inert background,
 * `Escape`, scroll lock, and focus returned to whatever opened it.
 *
 * ── The scrim is glass, and that is a decision about hierarchy ────────────────────────
 *
 * `backdrop-blur` at 20px — the larger of the two blurs in the reference's interaction evidence
 * — plus a dark wash. Blur rather than opacity alone because a dialog needs the page *behind*
 * it to stop competing for attention while still reading as present. Dimming alone flattens the
 * page into a grey rectangle; blurring keeps its shape and removes its detail, which is what
 * makes a modal feel layered rather than stacked.
 *
 * ── `forceMount` + `AnimatePresence`, and why `open` is a prop ────────────────────────
 *
 * Radix unmounts the moment `open` goes false, which would cut an exit animation off before its
 * first frame. `forceMount` hands mounting to `AnimatePresence` instead: it keeps the subtree
 * alive until every descendant `motion` component has finished exiting. That is why the caller's
 * `open` state has to be passed in — `AnimatePresence` needs something to render conditionally,
 * and Radix's internal state is not reachable from here.
 */

export const Dialog = DialogPrimitive.Root;
export const DialogTrigger = DialogPrimitive.Trigger;
export const DialogClose = DialogPrimitive.Close;

export function DialogTitle({
  className,
  ...props
}: React.ComponentPropsWithoutRef<typeof DialogPrimitive.Title>) {
  return (
    <DialogPrimitive.Title
      className={cn('text-title-2 font-[510] tracking-[-0.165px] text-fg', className)}
      {...props}
    />
  );
}

export function DialogDescription({
  className,
  ...props
}: React.ComponentPropsWithoutRef<typeof DialogPrimitive.Description>) {
  return (
    <DialogPrimitive.Description className={cn('text-body text-fg-subtle', className)} {...props} />
  );
}

export function DialogContent({
  className,
  children,
  open,
  showClose = true,
  ...props
}: React.ComponentPropsWithoutRef<typeof DialogPrimitive.Content> & {
  /** The caller's open state. Drives `AnimatePresence`; see the note above. */
  open: boolean;
  showClose?: boolean | undefined;
}) {
  return (
    <AnimatePresence>
      {open ? (
        <DialogPrimitive.Portal forceMount>
          <DialogPrimitive.Overlay asChild forceMount>
            <motion.div
              className="fixed inset-0 z-50 bg-black/40 backdrop-blur-[20px]"
              variants={scrimVariants}
              initial="hidden"
              animate="visible"
              exit="hidden"
            />
          </DialogPrimitive.Overlay>

          <DialogPrimitive.Content asChild forceMount {...props}>
            <motion.div
              className={cn(
                'gpu fixed top-1/2 left-1/2 z-50 w-full max-w-lg -translate-x-1/2 -translate-y-1/2',
                'rounded-xl bg-surface-elevated shadow-(--shadow-overlay)',
                className,
              )}
              variants={dialogVariants}
              initial="hidden"
              animate="visible"
              exit="hidden"
            >
              {children}
              {showClose ? (
                <DialogPrimitive.Close
                  className={cn(
                    'absolute top-3.5 right-3.5 rounded-sm text-fg-subtle',
                    'transition-colors duration-(--duration-fast) hover:text-fg',
                  )}
                >
                  <X className="size-4" />
                  <span className="sr-only">Close</span>
                </DialogPrimitive.Close>
              ) : null}
            </motion.div>
          </DialogPrimitive.Content>
        </DialogPrimitive.Portal>
      ) : null}
    </AnimatePresence>
  );
}

export function DialogHeader({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('flex flex-col gap-1.5 px-5 pt-5 pb-4', className)} {...props} />;
}

export function DialogFooter({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        'flex items-center justify-end gap-2 border-t border-border-subtle px-5 py-3.5',
        className,
      )}
      {...props}
    />
  );
}
