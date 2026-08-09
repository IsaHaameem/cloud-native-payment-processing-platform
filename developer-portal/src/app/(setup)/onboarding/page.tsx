import { KeyRound, ShieldCheck, TestTube2 } from 'lucide-react';
import type { Metadata } from 'next';
import { redirect } from 'next/navigation';

import { readCsrfToken } from '@/lib/security/csrf';
import { DEFAULT_AFTER_LOGIN } from '@/lib/security/redirect';
import { requireSession } from '@/lib/session/require';

import { OnboardingForm } from './onboarding-form';

export const metadata: Metadata = { title: 'Set up your business' };

/**
 * Merchant setup — the step between having an account and having somewhere to act (M23.2a).
 *
 * ── The reverse guard ─────────────────────────────────────────────────────────────────
 *
 * `requireMerchant` sends a merchant-less user here; this page sends a merchant-*ed* one back.
 * Without both halves the two guards bounce a user between `/dashboard` and `/onboarding`
 * forever, which is the classic redirect loop.
 *
 * ── Why it trusts the session and does not re-ask the platform ────────────────────────
 *
 * Asking merchant-service here would catch the one case the session gets wrong — a user who
 * onboarded in another tab, or whose cookie write was lost — and it could do nothing with the
 * answer. A Server Component cannot write a cookie, so it could not reseal the session; it could
 * only redirect, and every destination it might redirect to reads that same stale cookie. Sending
 * such a user to `/dashboard` bounces them back here, and sending them to `/login` bounces them
 * *through* the middleware's signed-in redirect and back here again. A lookup whose every branch
 * is a loop is a request spent to arrive nowhere.
 *
 * The recovery lives where a cookie can actually be written: submitting the form calls
 * `POST /api/v1/merchants`, the platform answers 409, and the action re-reads the merchant,
 * reseals the session and continues to the dashboard. One stale render, then correct forever.
 *
 * ── Not cached ────────────────────────────────────────────────────────────────────────
 *
 * It carries a CSRF token and asks a per-user question. Either alone would rule out a shared
 * cached copy.
 */
export const dynamic = 'force-dynamic';

export default async function OnboardingPage() {
  const session = await requireSession();

  if (session.merchantId !== undefined) redirect(DEFAULT_AFTER_LOGIN);

  const csrfToken = await readCsrfToken();

  return (
    <div className="w-full max-w-[860px]">
      <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_360px] lg:items-start lg:gap-12">
        <div className="lg:pt-4">
          <p className="text-label font-[510] text-accent-text">Step 2 of 2</p>
          <h1 className="mt-2 text-title-2 font-[510] tracking-[-0.165px] text-fg sm:text-[2rem] sm:leading-[1.15] sm:tracking-[-0.6px]">
            Set up your business
          </h1>
          <p className="mt-3 max-w-md text-body text-pretty text-fg-subtle">
            This is the account your payments, refunds and ledger entries belong to. It takes one
            form — there is no review queue and no waiting.
          </p>

          <ul className="mt-8 flex flex-col gap-4">
            <Point icon={TestTube2} title="You start in test mode">
              Every key, payment and webhook is sandboxed until you switch modes deliberately.
            </Point>
            <Point icon={KeyRound} title="API keys are issued immediately">
              A publishable and a secret key for each mode, created with the account.
            </Point>
            <Point icon={ShieldCheck} title="You can change this later">
              The business name and contact address are editable from settings.
            </Point>
          </ul>
        </div>

        <div className="rounded-xl bg-surface p-6 ring-hairline">
          <OnboardingForm csrfToken={csrfToken} suggestedEmail={session.email} />
        </div>
      </div>
    </div>
  );
}

function Point({
  icon: Icon,
  title,
  children,
}: {
  icon: React.ComponentType<{ className?: string; 'aria-hidden'?: boolean }>;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <li className="flex gap-3">
      <span className="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-md bg-surface-elevated text-fg-subtle ring-hairline">
        <Icon aria-hidden className="size-3.5" />
      </span>
      <span>
        <span className="block text-label font-[510] text-fg">{title}</span>
        <span className="mt-0.5 block text-label text-fg-subtle">{children}</span>
      </span>
    </li>
  );
}
