'use client';

import { motion, useScroll, useTransform } from 'framer-motion';
import { Menu, X } from 'lucide-react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import * as React from 'react';

import { Wordmark } from '@/components/layout/logo';
import { SITE_NAV } from '@/components/layout/nav-items';
import { Button } from '@/components/ui/button';
import { duration, ease } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * The public navbar (M23.2a; expanded to a multi-page marketing site).
 *
 * ── It changes once, at 8px of scroll ─────────────────────────────────────────────────
 *
 * Transparent and borderless at the top of the page so a hero starts flush with the viewport;
 * past a few pixels it takes the `glass` treatment and a hairline — the same backdrop blur the
 * product header uses, so the public and authenticated chrome read as one system. Driven by
 * `useScroll`/`useTransform` on the compositor, never a scroll listener setting React state.
 *
 * ── The full nav collapses below `lg` ────────────────────────────────────────────────
 *
 * Five section links plus Documentation plus the auth pair do not fit a tablet width, so below
 * `lg` the links move into a disclosure panel and only the wordmark, "Get started" and the
 * menu trigger stay on the bar.
 *
 * ── The right-hand pair is the whole point ───────────────────────────────────────────
 *
 * "Sign in" is quiet and "Get started" is the single accented control on the screen — the
 * reference's rule that the primary colour belongs to one action per screen.
 */
export function SiteHeader({ signedIn }: { signedIn: boolean }) {
  const { scrollY } = useScroll();
  const backgroundOpacity = useTransform(scrollY, [0, 8], [0, 1]);
  const pathname = usePathname();

  const [open, setOpen] = React.useState(false);

  // The drawer is `position: fixed`; without this the page behind it scrolls under the user's
  // finger, which on a phone reads as the menu having lost the page.
  React.useEffect(() => {
    if (!open) return;
    const { overflow } = document.body.style;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = overflow;
    };
  }, [open]);

  // Close the panel whenever the route changes — a menu left open over the page it navigated to
  // is the most common way a mobile nav feels broken.
  React.useEffect(() => setOpen(false), [pathname]);

  return (
    <header className="sticky top-0 z-40">
      <motion.div
        aria-hidden
        style={{ opacity: backgroundOpacity }}
        className="glass absolute inset-0 border-b border-border-subtle"
      />

      <div className="relative mx-auto flex h-14 w-full max-w-6xl items-center gap-6 px-5 sm:px-8">
        <Link href="/" aria-label="PaymentFlow home" className="shrink-0 rounded-md">
          <Wordmark />
        </Link>

        <nav aria-label="Site" className="hidden flex-1 items-center gap-1 lg:flex">
          {SITE_NAV.map((item) => {
            const active = pathname === item.href;
            return (
              <Link
                key={item.href}
                href={item.href}
                aria-current={active ? 'page' : undefined}
                className={cn(
                  'rounded-md px-2.5 py-1.5 text-label',
                  'transition-colors duration-(--duration-fast) ease-(--ease-out-quart)',
                  'hover:bg-surface-hover hover:text-fg',
                  active ? 'text-fg' : 'text-fg-subtle',
                )}
              >
                {item.label}
              </Link>
            );
          })}
        </nav>

        <div className="ml-auto flex items-center gap-2 lg:ml-0">
          <Link
            href="/docs"
            className="hidden rounded-md px-2.5 py-1.5 text-label text-fg-subtle transition-colors duration-(--duration-fast) hover:bg-surface-hover hover:text-fg lg:inline-flex"
          >
            Documentation
          </Link>

          {signedIn ? (
            <Button variant="primary" size="md" asChild>
              <Link href="/dashboard">Go to dashboard</Link>
            </Button>
          ) : (
            <>
              <Button variant="ghost" size="md" asChild className="hidden sm:inline-flex">
                <Link href="/login">Sign in</Link>
              </Button>
              <Button variant="primary" size="md" asChild>
                <Link href="/signup">Get started</Link>
              </Button>
            </>
          )}

          <Button
            variant="ghost"
            size="icon"
            className="lg:hidden"
            aria-expanded={open}
            aria-controls="site-mobile-nav"
            aria-label={open ? 'Close menu' : 'Open menu'}
            onClick={() => setOpen((previous) => !previous)}
          >
            {open ? <X /> : <Menu />}
          </Button>
        </div>
      </div>

      {/*
       * A disclosure panel, not a Radix `Sheet`. These are ordinary in-page links that close
       * themselves the moment one is followed; a modal would trap focus in a menu whose links
       * move the page behind the trap. So: `aria-expanded` on the trigger, `Escape` to close.
       */}
      {open ? (
        <motion.div
          id="site-mobile-nav"
          initial={{ opacity: 0, y: -4 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: duration.fast, ease: ease.outQuart }}
          onKeyDown={(event) => {
            if (event.key === 'Escape') setOpen(false);
          }}
          className="glass relative border-b border-border-subtle px-5 pb-4 lg:hidden"
        >
          <nav aria-label="Site" className="flex flex-col">
            {[...SITE_NAV, { label: 'Documentation', href: '/docs' }].map((item) => (
              <Link
                key={item.href}
                href={item.href}
                className="rounded-md px-2 py-2.5 text-body text-fg-muted transition-colors duration-(--duration-fast) hover:bg-surface-hover hover:text-fg"
              >
                {item.label}
              </Link>
            ))}
            {!signedIn ? (
              <Link
                href="/login"
                className="rounded-md px-2 py-2.5 text-body text-fg-muted transition-colors duration-(--duration-fast) hover:bg-surface-hover hover:text-fg sm:hidden"
              >
                Sign in
              </Link>
            ) : null}
          </nav>
        </motion.div>
      ) : null}
    </header>
  );
}
