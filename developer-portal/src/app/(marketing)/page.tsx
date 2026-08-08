'use client';

import { motion } from 'framer-motion';
import { ArrowRight, KeyRound, Layers, ShieldCheck } from 'lucide-react';
import Link from 'next/link';

import { Wordmark } from '@/components/layout/logo';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { listItemVariants, listVariants } from '@/lib/motion';

/**
 * The public entry point (M23.1, redesigned to the Linear system).
 *
 * Kept to what the milestone is about: it proves the design language reads as intended at page
 * scale — the 64px hero at -0.88px tracking, the accent used exactly once, the grid backdrop —
 * and gives `/` something to be other than a redirect to a login page that does not exist yet.
 * The marketing surface proper is M25's.
 *
 * ── The hero is the one place the display sizes appear ─────────────────────────────────
 *
 * `text-hero` (64px / 510 / -0.88px) is the extracted `display-hero` role, and the reference
 * only ever uses it here. Inside the product, headings are 18px and the density does the work —
 * which is why `PageHeader` does not reach for this scale.
 */
export default function LandingPage() {
  return (
    <div className="relative min-h-dvh overflow-hidden">
      <div aria-hidden className="bg-grid bg-grid-fade absolute inset-0 -z-10" />
      <div
        aria-hidden
        className="pointer-events-none absolute inset-x-0 -top-32 -z-10 h-96 bg-[radial-gradient(ellipse_45%_50%_at_50%_50%,var(--color-accent-subtle),transparent_70%)]"
      />

      <header className="mx-auto flex h-14 w-full max-w-5xl items-center justify-between px-6">
        <Wordmark />
        <Button variant="ghost" size="md" asChild>
          <Link href="/foundation">
            Design system <ArrowRight />
          </Link>
        </Button>
      </header>

      <motion.main
        id="main"
        variants={listVariants}
        initial="hidden"
        animate="visible"
        className="mx-auto w-full max-w-5xl px-6 pt-20 pb-28 sm:pt-28"
      >
        <motion.div variants={listItemVariants}>
          <Badge tone="accent">Developer portal · M23</Badge>
        </motion.div>

        <motion.h1
          variants={listItemVariants}
          className="mt-6 max-w-3xl text-[2.5rem] leading-[1.1] font-[510] tracking-[-0.88px] text-balance text-fg sm:text-hero"
        >
          The payments platform your engineers actually want to integrate
        </motion.h1>

        <motion.p
          variants={listItemVariants}
          className="mt-6 max-w-xl text-body-lg text-pretty text-fg-subtle"
        >
          Idempotent payments, a double-entry ledger, signed webhooks and a versioned public API —
          with a dashboard built on the same contract the SDKs are generated from.
        </motion.p>

        <motion.div variants={listItemVariants} className="mt-9 flex flex-wrap items-center gap-3">
          <Button variant="primary" size="lg" asChild>
            <Link href="/foundation">
              Explore the foundation <ArrowRight />
            </Link>
          </Button>
        </motion.div>

        <motion.div variants={listVariants} className="mt-24 grid gap-4 sm:grid-cols-3">
          {[
            {
              icon: Layers,
              title: 'One contract',
              body: 'The portal reads the same OpenAPI document the Node and Python SDKs are generated from.',
            },
            {
              icon: ShieldCheck,
              title: 'No token in the browser',
              body: 'Sessions live in an encrypted, http-only cookie. Every platform call is server-side.',
            },
            {
              icon: KeyRound,
              title: 'Test and live, never confused',
              body: 'Mode is bound to the credential and shown on every screen it affects.',
            },
          ].map(({ icon: Icon, title, body }) => (
            <motion.div key={title} variants={listItemVariants}>
              <Card className="h-full">
                <CardContent className="pt-5">
                  <Icon aria-hidden className="size-4 text-fg-subtle" />
                  <p className="mt-3 text-label font-[510] text-fg">{title}</p>
                  <p className="mt-1.5 text-body text-fg-subtle">{body}</p>
                </CardContent>
              </Card>
            </motion.div>
          ))}
        </motion.div>
      </motion.main>
    </div>
  );
}
