'use client';

import { motion } from 'framer-motion';
import { Check, Sparkles, Play } from 'lucide-react';
import Link from 'next/link';

import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { listItemVariants, listVariants } from '@/lib/motion';
import { type Mode } from '@/lib/session/session';

/**
 * What the Overview shows until there is real activity to show instead.
 *
 * Redesigned for the integration UX pass: a short checklist of where the account is, and **one
 * primary next action** — run the quickstart — with "let an AI agent do it" beside it. The API
 * detail that used to live here now lives on `/developers/quickstart`, where a first-time
 * integrator is looking for it.
 */
export function GettingStarted({ mode }: { mode: Mode }) {
  return (
    <motion.div
      variants={listVariants}
      initial="hidden"
      animate="visible"
      className="mt-10 grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,320px)_minmax(0,1fr)]"
    >
      <motion.div variants={listItemVariants}>
        <Card className="h-full">
          <CardContent className="pt-5">
            <p className="text-label font-[510] text-fg">Getting started</p>
            <ol className="mt-4 flex flex-col gap-3.5">
              <Step done>Account created</Step>
              <Step done>Business account created</Step>
              <Step>Send your first test payment</Step>
              <Step>Point a webhook at your server</Step>
            </ol>
            <p className="mt-5 text-label-sm text-fg-faint">
              You are in <span className="font-[510] text-fg-subtle">{mode} mode</span>. No real
              money moves.
            </p>
          </CardContent>
        </Card>
      </motion.div>

      <motion.div variants={listItemVariants}>
        <Card className="h-full">
          <CardContent className="flex h-full flex-col pt-5">
            <p className="text-label font-[510] text-fg">Connect PaymentFlow</p>
            <p className="mt-1.5 max-w-md text-label text-fg-subtle">
              Create a test key, put it on your server, and send a payment. Follow the quickstart
              yourself, or hand a generated prompt to your coding agent.
            </p>

            <div className="mt-4 flex flex-1 flex-col justify-end gap-2 sm:flex-row sm:items-center">
              <Button variant="primary" size="lg" asChild>
                <Link href="/developers/quickstart">
                  <Play /> Run the quickstart
                </Link>
              </Button>
              <Button variant="secondary" size="lg" asChild>
                <Link href="/developers/ai">
                  <Sparkles /> Integrate with AI
                </Link>
              </Button>
            </div>
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
