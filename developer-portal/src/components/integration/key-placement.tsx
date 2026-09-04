import { ArrowRight } from 'lucide-react';
import Link from 'next/link';
import * as React from 'react';

import { EnvVarField } from '@/components/integration/env-var-field';
import { Card, CardContent } from '@/components/ui/card';
import type { Mode } from '@/lib/session/session';

/**
 * "Where does this key go?" — shown on the API keys page so a first-time integrator does not
 * have to work it out. Plain-language test vs live, the exact `.env` line, and the one rule.
 */
export function KeyPlacement({ mode }: { mode: Mode }) {
  return (
    <Card className="mb-6">
      <CardContent className="pt-5">
        <p className="text-label font-[510] text-fg">Where does this key go?</p>

        <div className="mt-3 grid gap-3 sm:grid-cols-2">
          <div className="rounded-lg bg-surface-inset p-3 ring-hairline">
            <p className="text-label-sm font-[510] text-mode-test">Test key — sk_test_…</p>
            <p className="mt-1 text-label-sm text-fg-subtle">
              Safe for development. No real money moves. Use this while you build and test.
            </p>
          </div>
          <div className="rounded-lg bg-surface-inset p-3 ring-hairline">
            <p className="text-label-sm font-[510] text-fg">Live key — sk_live_…</p>
            <p className="mt-1 text-label-sm text-fg-subtle">
              For going live. The current PaymentFlow platform still uses a simulated acquirer, so
              no real funds move in either mode.
            </p>
          </div>
        </div>

        <div className="mt-4">
          <EnvVarField name="PAYMENTFLOW_API_KEY" value={`sk_${mode}_your_key_here`} />
        </div>

        <Link
          href="/developers/quickstart"
          className="mt-4 inline-flex items-center gap-1 text-label-sm font-[510] text-accent-text hover:underline"
        >
          Next: use it in the quickstart <ArrowRight className="size-3.5" />
        </Link>
      </CardContent>
    </Card>
  );
}
