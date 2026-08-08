'use client';

import { Menu } from 'lucide-react';
import * as React from 'react';

import { Wordmark } from '@/components/layout/logo';
import { SidebarNav } from '@/components/layout/sidebar-nav';
import { Button } from '@/components/ui/button';
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetTitle,
  SheetTrigger,
} from '@/components/ui/sheet';

/**
 * The navigation below `lg` (M23.1, redesigned).
 *
 * Closes on navigation — a drawer left open over the page it just navigated to is the most
 * common way a mobile nav feels broken. `SidebarNav`'s `onNavigate` exists for this and for
 * nothing else.
 *
 * The open state is held here rather than left to Radix because `SheetContent` needs it: an
 * exit animation requires `AnimatePresence` to outlive the unmount, which means the caller owns
 * the state. That is the cost of the drawer sliding out instead of vanishing, and for a panel
 * covering a third of the viewport it is worth paying.
 *
 * Title and description are visually hidden rather than absent: Radix's dialog requires both
 * for its `aria-labelledby`/`aria-describedby` wiring, and omitting them trades a console
 * warning for a drawer a screen reader cannot introduce.
 */
export function MobileNav() {
  const [open, setOpen] = React.useState(false);

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetTrigger asChild>
        <Button variant="ghost" size="icon" aria-label="Open navigation" className="lg:hidden">
          <Menu />
        </Button>
      </SheetTrigger>
      <SheetContent side="left" open={open} className="p-0">
        <div className="flex h-12 shrink-0 items-center px-4">
          <Wordmark />
        </div>
        <SheetTitle className="sr-only">Navigation</SheetTitle>
        <SheetDescription className="sr-only">
          Links to every area of the PaymentFlow dashboard.
        </SheetDescription>
        <SidebarNav onNavigate={() => setOpen(false)} />
      </SheetContent>
    </Sheet>
  );
}
