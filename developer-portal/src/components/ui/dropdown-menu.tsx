'use client';

import * as DropdownMenuPrimitive from '@radix-ui/react-dropdown-menu';
import { motion } from 'framer-motion';
import { Check } from 'lucide-react';
import * as React from 'react';

import { overlayVariants } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * A menu (M23.1, redesigned to the Linear system).
 *
 * On Radix for the behaviour that is invisible in a screenshot and decisive without a mouse:
 * typeahead, arrow-key roving focus, `Escape`, correct `aria-activedescendant`, and focus
 * returned to the trigger.
 *
 * ── Animating it ──────────────────────────────────────────────────────────────────────
 *
 * The content is a `motion.div` handed to Radix through `asChild`, animating only on enter.
 * There is no exit animation, and that is deliberate rather than an omission: an exit needs
 * `forceMount` plus an `AnimatePresence` that owns Radix's open state, which means every
 * caller has to become a controlled component. For a menu that closes in 80ms the trade is not
 * worth it — the enter is what the eye reads, and the close is instant either way.
 *
 * The surface is `background-elevated` (#23252a), the role the reference names for "elevated
 * panels, sidebar, and modal surfaces". `origin` comes from Radix's own CSS variable so the
 * scale grows out of the trigger rather than out of the menu's centre.
 */

export const DropdownMenu = DropdownMenuPrimitive.Root;
export const DropdownMenuTrigger = DropdownMenuPrimitive.Trigger;
export const DropdownMenuGroup = DropdownMenuPrimitive.Group;

export function DropdownMenuContent({
  className,
  sideOffset = 6,
  children,
  ...props
}: React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Content>) {
  return (
    <DropdownMenuPrimitive.Portal>
      <DropdownMenuPrimitive.Content sideOffset={sideOffset} asChild {...props}>
        <motion.div
          className={cn(
            'gpu z-50 min-w-44 overflow-hidden rounded-lg bg-surface-elevated p-1',
            'origin-(--radix-dropdown-menu-content-transform-origin)',
            'shadow-(--shadow-overlay)',
            className,
          )}
          variants={overlayVariants}
          initial="hidden"
          animate="visible"
        >
          {children}
        </motion.div>
      </DropdownMenuPrimitive.Content>
    </DropdownMenuPrimitive.Portal>
  );
}

export function DropdownMenuItem({
  className,
  ...props
}: React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Item>) {
  return (
    <DropdownMenuPrimitive.Item
      className={cn(
        'relative flex cursor-default items-center gap-2 rounded-sm px-2 py-1.5',
        'text-label font-[510] text-fg-muted outline-none select-none',
        'transition-colors duration-(--duration-instant)',
        'data-highlighted:bg-surface-hover data-highlighted:text-fg',
        'data-disabled:pointer-events-none data-disabled:opacity-40',
        '[&_svg]:size-4 [&_svg]:shrink-0 [&_svg]:text-fg-subtle',
        'data-highlighted:[&_svg]:text-fg',
        className,
      )}
      {...props}
    />
  );
}

export function DropdownMenuCheckboxItem({
  className,
  children,
  ...props
}: React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.CheckboxItem>) {
  return (
    <DropdownMenuPrimitive.CheckboxItem
      className={cn(
        'relative flex cursor-default items-center gap-2 rounded-sm py-1.5 pr-2 pl-7',
        'text-label font-[510] text-fg-muted outline-none select-none',
        'data-highlighted:bg-surface-hover data-highlighted:text-fg',
        '[&_svg]:size-4 [&_svg]:shrink-0',
        className,
      )}
      {...props}
    >
      <span className="absolute left-2 flex size-3.5 items-center justify-center">
        <DropdownMenuPrimitive.ItemIndicator>
          <Check className="size-3.5" />
        </DropdownMenuPrimitive.ItemIndicator>
      </span>
      {children}
    </DropdownMenuPrimitive.CheckboxItem>
  );
}

export function DropdownMenuLabel({
  className,
  ...props
}: React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Label>) {
  return (
    <DropdownMenuPrimitive.Label
      className={cn(
        'px-2 py-1.5 text-caption font-[510] tracking-wide text-fg-subtle uppercase',
        className,
      )}
      {...props}
    />
  );
}

export function DropdownMenuSeparator({
  className,
  ...props
}: React.ComponentPropsWithoutRef<typeof DropdownMenuPrimitive.Separator>) {
  return (
    <DropdownMenuPrimitive.Separator
      className={cn('-mx-1 my-1 h-px bg-border', className)}
      {...props}
    />
  );
}
