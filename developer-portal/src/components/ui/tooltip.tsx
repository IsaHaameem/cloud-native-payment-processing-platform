'use client';

import * as TooltipPrimitive from '@radix-ui/react-tooltip';
import { motion } from 'framer-motion';
import * as React from 'react';

import { overlayVariants } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * A tooltip (M23.1, redesigned to the Linear system) — what an icon-only control says when the
 * sidebar is collapsed.
 *
 * Radix gives it the part that matters and is easy to omit: the tip is wired to its trigger by
 * `aria-describedby` and opens on focus as well as hover, so a keyboard user gets the label a
 * mouse user gets. An icon button whose only label is a tooltip that never opens on focus is an
 * unlabelled button.
 *
 * `background-elevated` and a 12px label, matching the menu — a tooltip and a menu appearing
 * from the same rail should look like the same system.
 */

export const TooltipProvider = TooltipPrimitive.Provider;
export const Tooltip = TooltipPrimitive.Root;
export const TooltipTrigger = TooltipPrimitive.Trigger;

export function TooltipContent({
  className,
  sideOffset = 8,
  children,
  ...props
}: React.ComponentPropsWithoutRef<typeof TooltipPrimitive.Content>) {
  return (
    <TooltipPrimitive.Portal>
      <TooltipPrimitive.Content sideOffset={sideOffset} asChild {...props}>
        <motion.div
          className={cn(
            'gpu z-50 overflow-hidden rounded-md bg-surface-elevated px-2 py-1',
            'origin-(--radix-tooltip-content-transform-origin)',
            'text-label-sm font-[510] text-fg shadow-(--shadow-overlay)',
            className,
          )}
          variants={overlayVariants}
          initial="hidden"
          animate="visible"
        >
          {children}
        </motion.div>
      </TooltipPrimitive.Content>
    </TooltipPrimitive.Portal>
  );
}
