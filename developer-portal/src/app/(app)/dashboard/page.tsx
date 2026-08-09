import { Building2, CreditCard, KeyRound } from 'lucide-react';
import type { Metadata } from 'next';

import { PageHeader } from '@/components/patterns/page-header';
import { Card, CardContent } from '@/components/ui/card';
import { currentMerchant } from '@/lib/platform/current-merchant';
import { requireMerchant } from '@/lib/session/require';

import { GettingStarted } from './getting-started';

export const metadata: Metadata = { title: 'Overview' };

/**
 * The authenticated entry point (M23.2a).
 *
 * ── What this page is, and what it is not yet ─────────────────────────────────────────
 *
 * §6.2 specifies the Overview as today's volume, success rate, recent payments, webhook health
 * and quota headroom — five tiles reading four services through the data layer M23.3 builds.
 * None of that exists yet, and this page does not pretend otherwise: **there is not one
 * fabricated number on it.** A placeholder chart showing invented volume is worse than an empty
 * screen, because it is a lie told to the person least able to check it, on the surface whose
 * entire job is to be trusted about money.
 *
 * What it shows instead is everything that *is* true today — the merchant this session acts as,
 * the mode it is bound to, and the shortest real path to a first payment. §6.2 already asks for
 * exactly this for a brand-new account: "a clear empty state that guides toward the quickstart
 * rather than showing zeros". This is that state, for every account, until there is something
 * real to replace it with.
 *
 * ── `requireMerchant`, not `requireSession` ───────────────────────────────────────────
 *
 * This is the first page in the portal that genuinely needs a merchant, and it is the guard's
 * first caller. It is also what closes the entry flow: a user who registered a minute ago has a
 * session and no merchant, and this is the redirect that routes them to `/onboarding` instead of
 * showing them an application with nothing in it.
 */
export default async function DashboardPage() {
  const session = await requireMerchant();
  const lookup = await currentMerchant(session.accessToken);
  const merchant = lookup.status === 'found' ? lookup.merchant : undefined;

  return (
    <div>
      {/*
       * "Overview", not the business name. The shell already states which business this session
       * acts as, in the header, on every page — repeating it as the page title says the same
       * thing twice and leaves the page without a name of its own. The heading matches the
       * navigation item that leads here, which is what makes "where am I" answerable.
       */}
      <PageHeader
        title="Overview"
        description="Everything below is real — nothing on this page is sample data."
      />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <Fact icon={Building2} label="Business">
          {merchant?.businessName ?? 'Unavailable'}
        </Fact>
        <Fact icon={CreditCard} label="Merchant ID">
          <span className="tabular font-mono text-label">{session.merchantId}</span>
        </Fact>
        <Fact icon={KeyRound} label="Contact">
          {merchant?.contactEmail ?? session.email}
        </Fact>
      </div>

      <GettingStarted mode={session.mode} />
    </div>
  );
}

function Fact({
  icon: Icon,
  label,
  children,
}: {
  icon: React.ComponentType<{ className?: string; 'aria-hidden'?: boolean }>;
  label: string;
  children: React.ReactNode;
}) {
  return (
    <Card>
      <CardContent className="pt-5">
        <div className="flex items-center gap-2 text-fg-subtle">
          <Icon aria-hidden className="size-3.5" />
          <span className="text-label-sm font-[510] tracking-[0.02em] uppercase">{label}</span>
        </div>
        <p className="mt-2 truncate text-body text-fg">{children}</p>
      </CardContent>
    </Card>
  );
}
