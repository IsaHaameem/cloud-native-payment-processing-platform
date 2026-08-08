'use client';

import * as ContextMenuPrimitive from '@radix-ui/react-context-menu';
import { motion } from 'framer-motion';
import * as React from 'react';

import { overlayVariants } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * A contextual action menu (M23.1 redesign) — right-click on a table row.
 *
 * ── Why a context menu rather than a row of buttons ───────────────────────────────────
 *
 * A dense table cannot afford a visible action column: it costs width on every row to serve the
 * rare row someone acts on. A right-click menu costs nothing until it is wanted. Radix also
 * binds the long-press equivalent on touch, so the same affordance survives on a phone where
 * there is no right button.
 *
 * It is deliberately **not the only way to reach an action** — M23.7's detail page has the same
 * operations as ordinary buttons. A menu that hides the only path to a refund would be a menu
 * that hides the product.
 *
 * Radix supplies the parts that are invisible and decisive: typeahead, roving focus, `Escape`,
 * collision-aware placement, and focus returned to the trigger.
 */

export const ContextMenu = ContextMenuPrimitive.Root;
export const ContextMenuTrigger = ContextMenuPrimitive.Trigger;
export const ContextMenuGroup = ContextMenuPrimitive.Group;

export function ContextMenuContent({
  className,
  children,
  ...props
}: React.ComponentPropsWithoutRef<typeof ContextMenuPrimitive.Content>) {
  return (
    <ContextMenuPrimitive.Portal>
      <ContextMenuPrimitive.Content asChild {...props}>
        <motion.div
          className={cn(
            'gpu z-50 min-w-48 overflow-hidden rounded-lg bg-surface-elevated p-1',
            'origin-(--radix-context-menu-content-transform-origin)',
            'shadow-(--shadow-overlay)',
            className,
          )}
          variants={overlayVariants}
          initial="hidden"
          animate="visible"
        >
          {children}
        </motion.div>
      </ContextMenuPrimitive.Content>
    </ContextMenuPrimitive.Portal>
  );
}

export function ContextMenuItem({
  className,
  destructive = false,
  ...props
}: React.ComponentPropsWithoutRef<typeof ContextMenuPrimitive.Item> & {
  /** Renders in the danger tone. Destructive items still get a confirmation before they act. */
  destructive?: boolean | undefined;
}) {
  return (
    <ContextMenuPrimitive.Item
      className={cn(
        'relative flex cursor-default items-center gap-2.5 rounded-sm px-2 py-1.5',
        'text-label text-fg-muted outline-none select-none',
        'transition-colors duration-(--duration-instant)',
        'data-highlighted:bg-surface-hover data-highlighted:text-fg',
        'data-disabled:pointer-events-none data-disabled:opacity-40',
        '[&_svg]:size-3.5 [&_svg]:shrink-0 [&_svg]:text-fg-subtle',
        'data-highlighted:[&_svg]:text-fg',
        destructive &&
          'text-danger data-highlighted:bg-danger-surface data-highlighted:text-danger [&_svg]:text-danger data-highlighted:[&_svg]:text-danger',
        className,
      )}
      {...props}
    />
  );
}

export function ContextMenuLabel({
  className,
  ...props
}: React.ComponentPropsWithoutRef<typeof ContextMenuPrimitive.Label>) {
  return (
    <ContextMenuPrimitive.Label
      className={cn(
        'px-2 py-1.5 text-caption font-[510] tracking-[0.04em] text-fg-subtle uppercase',
        className,
      )}
      {...props}
    />
  );
}

export function ContextMenuSeparator({
  className,
  ...props
}: React.ComponentPropsWithoutRef<typeof ContextMenuPrimitive.Separator>) {
  return (
    <ContextMenuPrimitive.Separator
      className={cn('-mx-1 my-1 h-px bg-border-subtle', className)}
      {...props}
    />
  );
}

export function ContextMenuShortcut({
  className,
  ...props
}: React.HTMLAttributes<HTMLSpanElement>) {
  return (
    <span
      className={cn('ml-auto text-caption font-[510] tracking-wide text-fg-faint', className)}
      {...props}
    />
  );
}
