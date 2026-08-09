'use client';

import { motion } from 'framer-motion';
import { ArrowRight } from 'lucide-react';
import Link from 'next/link';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { API_VERSION } from '@/generated/contract';
import { duration, ease, listItemVariants, listVariants } from '@/lib/motion';

/**
 * The hero (M23.2a).
 *
 * ── What it claims ────────────────────────────────────────────────────────────────────
 *
 * Every noun in the supporting line is something in this repository: idempotency records in
 * `payment-service`, a double-entry ledger in `transaction-service`, HMAC-signed deliveries in
 * `notification-service`, a dated revision published in `docs/openapi.yaml`. A landing page for
 * an API is read by people who will find out, and the fastest way to lose them is a capability
 * list that the first hour of integration contradicts.
 *
 * ── The visual is the API, not an illustration ────────────────────────────────────────
 *
 * The request/response pair beside the copy is the actual contract: `POST /v1/payments`, the
 * `PaymentFlow-Version` header carrying the generated `API_VERSION`, a required `Idempotency-Key`,
 * `amountMinor` as an integer in the currency's minor unit, and a status from `PaymentStatus`'s
 * own wire spelling. It is a screenshot of the product rather than a drawing of one — which is
 * both more honest and, for this audience, more persuasive than any abstract graphic.
 *
 * ── The motion budget ─────────────────────────────────────────────────────────────────
 *
 * One staggered entrance on mount, and one slow ambient drift on the glow behind it. Nothing
 * loops in the reader's field of view, nothing parallaxes, and the total travel of any element is
 * 6px. `MotionConfig reducedMotion="user"` removes the transforms and keeps the fades, which
 * leaves the page fully intact — the entrance was never carrying meaning.
 *
 * ── It knows whether it is talking to a customer ──────────────────────────────────────
 *
 * A signed-in visitor is offered their dashboard rather than an account they already have.
 * Leaving the CTA as "Get started" is not merely untidy: the middleware redirects a signed-in
 * visitor away from `/signup`, so the button would be a link that visibly does something other
 * than what it says.
 */
export function Hero({ signedIn }: { signedIn: boolean }) {
  return (
    <section className="relative overflow-hidden">
      <div aria-hidden className="bg-grid bg-grid-fade absolute inset-0 -z-10" />

      {/*
       * The one ambient animation on the page: a wash that breathes over 12 seconds. Slow enough
       * that it is never the thing being looked at, and confined to opacity and scale so it costs
       * the compositor and nothing else.
       */}
      <motion.div
        aria-hidden
        initial={{ opacity: 0.5, scale: 1 }}
        animate={{ opacity: [0.5, 0.85, 0.5], scale: [1, 1.06, 1] }}
        transition={{ duration: 12, ease: 'easeInOut', repeat: Infinity }}
        className="pointer-events-none absolute inset-x-0 -top-40 -z-10 h-[28rem] bg-[radial-gradient(ellipse_42%_50%_at_50%_50%,var(--color-accent-subtle),transparent_70%)]"
      />

      <motion.div
        variants={listVariants}
        initial="hidden"
        animate="visible"
        className="mx-auto grid w-full max-w-6xl gap-12 px-5 pt-20 pb-24 sm:px-8 sm:pt-28 lg:grid-cols-[minmax(0,1fr)_minmax(0,520px)] lg:items-center lg:gap-16"
      >
        <div>
          <motion.div variants={listItemVariants}>
            <Badge tone="accent">Test mode by default</Badge>
          </motion.div>

          <motion.h1
            variants={listItemVariants}
            className="mt-6 max-w-2xl text-[2.5rem] leading-[1.08] font-[510] tracking-[-0.88px] text-balance text-fg sm:text-[3.5rem]"
          >
            Payments infrastructure built for developers
          </motion.h1>

          <motion.p
            variants={listItemVariants}
            className="mt-6 max-w-lg text-body-lg text-pretty text-fg-subtle"
          >
            Authorize, capture and refund through one idempotent API. Every movement of money is
            posted to a double-entry ledger and announced by a signed webhook — and none of it
            touches real funds until you switch out of test mode.
          </motion.p>

          <motion.div
            variants={listItemVariants}
            className="mt-9 flex flex-wrap items-center gap-3"
          >
            <Button variant="primary" size="lg" asChild>
              <Link href={signedIn ? '/dashboard' : '/signup'}>
                {signedIn ? 'Go to dashboard' : 'Get started'} <ArrowRight />
              </Link>
            </Button>
            <Button variant="secondary" size="lg" asChild>
              <a href="#lifecycle">See how it works</a>
            </Button>
          </motion.div>

          <motion.p variants={listItemVariants} className="mt-6 text-label text-fg-faint">
            Free in test mode · No card required · Revision{' '}
            <span className="tabular font-mono">{API_VERSION}</span>
          </motion.p>
        </div>

        <motion.div variants={listItemVariants}>
          <RequestPreview />
        </motion.div>
      </motion.div>
    </section>
  );
}

/**
 * The request/response pair.
 *
 * Rendered as text in a bordered panel rather than as a syntax-highlighted editor, because a
 * highlighter is a dependency and a theme to maintain for eleven lines. The one colour used is
 * the accent on the method and the success token on the status — enough to make the pair
 * scannable, and both are semantic tokens so they follow the theme.
 */
function RequestPreview() {
  return (
    <div className="edge-light relative overflow-hidden rounded-xl bg-surface ring-hairline">
      <div className="flex items-center gap-2 border-b border-border-subtle px-4 py-2.5">
        <span aria-hidden className="size-2 rounded-full bg-border-strong" />
        <span aria-hidden className="size-2 rounded-full bg-border-strong" />
        <span aria-hidden className="size-2 rounded-full bg-border-strong" />
        <span className="ml-1 text-label-sm text-fg-faint">create a payment</span>
      </div>

      <div className="overflow-x-auto px-4 py-4">
        <pre className="font-mono text-label-sm leading-relaxed whitespace-pre text-fg-muted">
          <span className="text-accent-text">POST</span> /v1/payments{'\n'}
          PaymentFlow-Version: {API_VERSION}
          {'\n'}
          Idempotency-Key: a3f1c7e2-…{'\n'}
          {'\n'}
          {'{'} &quot;amountMinor&quot;: 1000, &quot;currency&quot;: &quot;USD&quot; {'}'}
        </pre>
      </div>

      <div className="border-t border-border-subtle bg-surface-inset px-4 py-4">
        <motion.pre
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: 0.5, duration: duration.base, ease: ease.outQuart }}
          className="overflow-x-auto font-mono text-label-sm leading-relaxed whitespace-pre text-fg-muted"
        >
          <span className="text-success">201 Created</span>
          {'\n'}
          {'{'}
          {'\n'}
          {'  '}&quot;id&quot;: &quot;pay_3KxQ8vLm2Wd&quot;,{'\n'}
          {'  '}&quot;status&quot;: <span className="text-fg">&quot;authorized&quot;</span>,{'\n'}
          {'  '}&quot;amountMinor&quot;: 1000,{'\n'}
          {'  '}&quot;currency&quot;: &quot;USD&quot;{'\n'}
          {'}'}
        </motion.pre>
      </div>
    </div>
  );
}
