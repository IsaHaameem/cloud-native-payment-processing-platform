'use client';

import * as SheetPrimitive from '@radix-ui/react-dialog';
import { AnimatePresence, motion } from 'framer-motion';
import { X } from 'lucide-react';
import * as React from 'react';

import { duration, ease } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * A panel that slides in from an edge (M23.1, redesigned) — the mobile sidebar, and later any
 * drawer.
 *
 * Built on Radix's dialog, and that is the whole reason it exists as a separate primitive: a
 * drawer needs a focus trap, an inert background, `Escape` to close, scroll locking, and focus
 * returned to whatever opened it. Every one of those is a way to make a keyboard user stuck, and
 * none of them is visible in a screenshot.
 *
 * The exit animation matters more here than anywhere else in the system: this panel covers a
 * third of the viewport, and something that large disappearing between frames reads as a glitch
 * rather than as a dismissal. See `dialog.tsx` for why that costs a `forceMount` and an `open`
 * prop.
 *
 * The scrim blurs at 4px — the smaller of the two blurs in the reference's interaction evidence.
 * A drawer leaves most of the page visible on purpose, so it dims and softens rather than hiding.
 */

export const Sheet = SheetPrimitive.Root;
export const SheetTrigger = SheetPrimitive.Trigger;
export const SheetClose = SheetPrimitive.Close;
export const SheetTitle = SheetPrimitive.Title;
export const SheetDescription = SheetPrimitive.Description;

export function SheetContent({
  className,
  children,
  side = 'left',
  open,
  ...props
}: React.ComponentPropsWithoutRef<typeof SheetPrimitive.Content> & {
  side?: 'left' | 'right' | undefined;
  /** The caller's open state. Drives `AnimatePresence`; see `dialog.tsx`. */
  open: boolean;
}) {
  const offscreen = side === 'left' ? '-100%' : '100%';

  return (
    <AnimatePresence>
      {open ? (
        <SheetPrimitive.Portal forceMount>
          <SheetPrimitive.Overlay asChild forceMount>
            <motion.div
              className="fixed inset-0 z-50 bg-black/50 backdrop-blur-[4px]"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: duration.fast }}
            />
          </SheetPrimitive.Overlay>

          <SheetPrimitive.Content asChild forceMount {...props}>
            <motion.div
              className={cn(
                'gpu fixed inset-y-0 z-50 flex w-72 max-w-[85vw] flex-col bg-surface-elevated',
                'shadow-(--shadow-overlay)',
                side === 'left' ? 'left-0' : 'right-0',
                className,
              )}
              initial={{ x: offscreen }}
              animate={{ x: 0 }}
              exit={{ x: offscreen }}
              transition={{ duration: duration.base, ease: ease.outQuart }}
            >
              {children}
              <SheetPrimitive.Close
                className={cn(
                  'absolute top-3.5 right-3 rounded-sm text-fg-subtle',
                  'transition-colors duration-(--duration-fast) hover:text-fg',
                )}
              >
                <X className="size-4" />
                <span className="sr-only">Close</span>
              </SheetPrimitive.Close>
            </motion.div>
          </SheetPrimitive.Content>
        </SheetPrimitive.Portal>
      ) : null}
    </AnimatePresence>
  );
}
