'use client';

import { motion, useScroll, useTransform } from 'framer-motion';
import { Menu, X } from 'lucide-react';
import Link from 'next/link';
import * as React from 'react';

import { Wordmark } from '@/components/layout/logo';
import { SITE_NAV } from '@/components/marketing/site-nav';
import { Button } from '@/components/ui/button';
import { duration, ease } from '@/lib/motion';
import { cn } from '@/lib/utils';

/**
 * The public navbar (M23.2a).
 *
 * ── It changes once, at 8px of scroll ─────────────────────────────────────────────────
 *
 * At the top of the page the bar is transparent and borderless, so the hero starts at the top of
 * the viewport rather than under a stripe. Past a few pixels it takes the `glass` treatment and a
 * hairline — the same backdrop blur the product header uses, so the public and authenticated
 * chrome are visibly one system.
 *
 * Driven by `useScroll` mapped through `useTransform` rather than by a scroll listener setting
 * React state: the listener version re-renders the whole subtree on every frame of a scroll,
 * which is the standard way a sticky header becomes the jankiest element on a page. This one
 * writes two style values on the compositor and never re-renders.
 *
 * ── Reduced motion is not a special case here ─────────────────────────────────────────
 *
 * The only movement is a 140ms opacity change on a background. `MotionConfig reducedMotion="user"`
 * in the providers drops transforms and keeps opacity, which is exactly the right degradation:
 * the bar still becomes legible over content, it simply does not animate into it.
 *
 * ── The right-hand pair is the whole point of the page ────────────────────────────────
 *
 * "Sign in" is quiet and "Get started" is the single accented control on the screen — the
 * reference's own rule that the primary colour belongs to one action per screen, applied to the
 * one action this page exists to produce.
 */
export function SiteHeader({ signedIn }: { signedIn: boolean }) {
  const { scrollY } = useScroll();
  const backgroundOpacity = useTransform(scrollY, [0, 8], [0, 1]);

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

        <nav aria-label="Site" className="hidden flex-1 items-center gap-1 md:flex">
          {SITE_NAV.map((item) => (
            <a
              key={item.href}
              href={item.href}
              className={cn(
                'rounded-md px-2.5 py-1.5 text-label text-fg-subtle',
                'transition-colors duration-(--duration-fast) ease-(--ease-out-quart)',
                'hover:bg-surface-hover hover:text-fg',
              )}
            >
              {item.label}
            </a>
          ))}
        </nav>

        <div className="ml-auto flex items-center gap-2 md:ml-0">
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
            className="md:hidden"
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
       * A panel rather than a Radix `Sheet`.
       *
       * The product's drawer is a modal dialog, and correctly so: it covers the application, so
       * it needs a focus trap, scroll lock and `aria-modal`. This one is four in-page anchors
       * that close themselves the moment one is followed. Making it a modal would trap focus in
       * a menu whose links move the page *behind* the trap, and would announce a dialog for what
       * is a disclosure. So: `aria-expanded` on the trigger, `Escape` to close, and the links
       * are ordinary links.
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
          className="glass relative border-b border-border-subtle px-5 pb-4 md:hidden"
        >
          <nav aria-label="Site" className="flex flex-col">
            {SITE_NAV.map((item) => (
              <a
                key={item.href}
                href={item.href}
                onClick={() => setOpen(false)}
                className="rounded-md px-2 py-2.5 text-body text-fg-muted transition-colors duration-(--duration-fast) hover:bg-surface-hover hover:text-fg"
              >
                {item.label}
              </a>
            ))}
            {!signedIn ? (
              <Link
                href="/login"
                onClick={() => setOpen(false)}
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
