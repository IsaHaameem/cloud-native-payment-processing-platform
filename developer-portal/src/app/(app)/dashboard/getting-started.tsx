'use client';

import { motion } from 'framer-motion';
import { Check } from 'lucide-react';

import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { API_VERSION, DEFAULT_BASE_URL } from '@/generated/contract';
import { listItemVariants, listVariants } from '@/lib/motion';
import { type Mode } from '@/lib/session/session';

/**
 * The quickstart the Overview shows until it has real figures to show instead (M23.2a).
 *
 * ── Every value in the snippet comes from the contract ────────────────────────────────
 *
 * `API_VERSION` and `DEFAULT_BASE_URL` are imported from `@/generated/contract`, which
 * `:sdks:shared` writes from `docs/openapi.yaml` and `verifySdkSources` fails the build over when
 * it is stale. So the revision date on this screen cannot drift away from the one the platform is
 * actually serving — which is the failure mode of every hand-written code sample ever shipped.
 *
 * The request itself is `createPayment` as the contract publishes it: `POST /v1/payments`, a
 * required `Idempotency-Key`, `currency` the only required body field, `amountMinor` an integer
 * in the currency's minor unit, and `tok_visa_approved` from the test-card catalogue at
 * `GET /v1/test/cards`. Nothing here is illustrative.
 *
 * ── The key placeholder is a placeholder ──────────────────────────────────────────────
 *
 * `sk_test_…` is not a real key and no real key is fetched to put here. Secret keys are stored
 * SHA-256 hashed and revealed exactly once, at the moment they are issued; a dashboard that could
 * print one would be a dashboard that had stored one.
 */
export function GettingStarted({ mode }: { mode: Mode }) {
  const snippet = [
    `curl ${DEFAULT_BASE_URL}/v1/payments \\`,
    `  -H "Authorization: Bearer sk_${mode}_…" \\`,
    `  -H "PaymentFlow-Version: ${API_VERSION}" \\`,
    `  -H "Idempotency-Key: $(uuidgen)" \\`,
    `  -H "Content-Type: application/json" \\`,
    `  -d '{"amountMinor": 1000, "currency": "USD",`,
    `       "paymentMethodToken": "tok_visa_approved"}'`,
  ].join('\n');

  return (
    <motion.div
      variants={listVariants}
      initial="hidden"
      animate="visible"
      // Stretched rather than `items-start`: two panels side by side that end at different
      // heights read as one of them having failed to load.
      className="mt-10 grid gap-4 lg:grid-cols-[minmax(0,340px)_minmax(0,1fr)]"
    >
      <motion.div variants={listItemVariants}>
        <Card className="h-full">
          <CardContent className="pt-5">
            <p className="text-label font-[510] text-fg">Getting started</p>
            <ol className="mt-4 flex flex-col gap-3.5">
              <Step done>Account created</Step>
              <Step done>Business account created</Step>
              <Step>Create your first test payment</Step>
              <Step>Point a webhook endpoint at your server</Step>
            </ol>
            <p className="mt-5 text-label-sm text-fg-faint">
              Payments, refunds, keys and analytics get their own screens as they land. Until then
              every one of them is reachable through the API on the right.
            </p>
          </CardContent>
        </Card>
      </motion.div>

      <motion.div variants={listItemVariants}>
        <Card className="h-full">
          <CardContent className="pt-5">
            <div className="flex items-center justify-between gap-3">
              <p className="text-label font-[510] text-fg">Your first payment</p>
              <Badge tone={mode === 'test' ? 'test' : 'warning'} dot>
                {mode} mode
              </Badge>
            </div>

            {/*
             * The block scrolls inside itself rather than widening the page — a `curl` line is
             * longer than any column this grid can give it, and a dashboard that scrolls
             * sideways is a dashboard with a layout bug on every other screen too.
             */}
            <div className="mt-3 overflow-x-auto rounded-md bg-surface-inset p-3.5 ring-hairline">
              <pre className="font-mono text-label-sm leading-relaxed whitespace-pre text-fg-muted">
                {snippet}
              </pre>
            </div>

            <p className="mt-3 text-label-sm text-fg-faint">
              Secret keys are revealed once, when they are issued, and stored hashed — so no screen
              can show you an existing one. Rotate a key to obtain a new secret.
            </p>
          </CardContent>
        </Card>
      </motion.div>
    </motion.div>
  );
}

function Step({ done = false, children }: { done?: boolean; children: React.ReactNode }) {
  return (
    <li className="flex items-start gap-2.5">
      <span
        aria-hidden
        className={
          done
            ? 'mt-px flex size-4 shrink-0 items-center justify-center rounded-full bg-success-surface text-success'
            : 'mt-px size-4 shrink-0 rounded-full ring-1 ring-border-strong ring-inset'
        }
      >
        {done ? <Check className="size-2.5" strokeWidth={3} /> : null}
      </span>
      <span className={done ? 'text-label text-fg-faint line-through' : 'text-label text-fg'}>
        {children}
      </span>
      <span className="sr-only">{done ? '(done)' : '(not done yet)'}</span>
    </li>
  );
}
