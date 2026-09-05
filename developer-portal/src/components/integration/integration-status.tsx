import { KeyRound, Play, Sparkles, BookOpen } from 'lucide-react';
import Link from 'next/link';
import * as React from 'react';

import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import type { Mode } from '@/lib/session/session';

/**
 * "How connected am I?" — the first thing the Integration hub answers.
 *
 * Every value is real: `hasKey` and `keyCount` come from `listApiKeys`, `paymentCount` and
 * `lastPaymentAt` from `listPayments`. No fabrication — an account with nothing shows the
 * "not connected yet" state, not an invented success.
 */
export function IntegrationStatus({
  mode,
  hasKey,
  keyCount,
  paymentCount,
  lastPaymentAt,
}: {
  mode: Mode;
  hasKey: boolean;
  keyCount: number;
  paymentCount: number;
  lastPaymentAt?: string | undefined;
}) {
  const connected = hasKey;
  const sent = paymentCount > 0;

  return (
    <Card>
      <CardContent className="pt-5">
        <div className="flex flex-wrap items-center gap-2.5">
          <span
            aria-hidden
            className={cn(
              'size-2 rounded-full',
              connected ? 'bg-success' : 'bg-fg-faint ring-1 ring-border-strong ring-inset',
            )}
          />
          <p className="text-body-lg font-[510] text-fg">
            {connected
              ? `${mode === 'test' ? 'Test' : 'Live'} environment connected`
              : 'Not connected yet'}
          </p>
        </div>
        <p className="mt-1.5 max-w-xl text-label text-fg-subtle">
          {connected
            ? sent
              ? `Your ${keyCount === 1 ? 'key is' : 'keys are'} working — ${paymentCount} ${mode} payment${paymentCount === 1 ? '' : 's'} so far${lastPaymentAt ? `, most recent ${new Date(lastPaymentAt).toLocaleString()}` : ''}.`
              : `You have a ${mode}-mode key. Send your first test payment to confirm the integration end to end.`
            : `Create a ${mode}-mode key, put it on your server, and you're ready to send payments. No real money moves in ${mode} mode.`}
        </p>

        <div className="mt-4 flex flex-wrap gap-2">
          {!connected ? (
            <Button variant="primary" size="lg" asChild>
              <Link href="/developers/api-keys">
                <KeyRound /> Create a test key
              </Link>
            </Button>
          ) : (
            <Button variant="primary" size="lg" asChild>
              <Link href="/developers/quickstart">
                <Play /> Run a test payment
              </Link>
            </Button>
          )}
          <Button variant="secondary" size="lg" asChild>
            <Link href="/developers/ai">
              <Sparkles /> Integrate with AI
            </Link>
          </Button>
          <Button variant="ghost" size="lg" asChild>
            <Link href="/developers/quickstart">
              <BookOpen /> Read the quickstart
            </Link>
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
