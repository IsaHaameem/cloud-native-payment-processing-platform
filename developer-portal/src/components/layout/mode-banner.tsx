import { FlaskConical, Radio } from 'lucide-react';

import { cn } from '@/lib/utils';

/**
 * The mode banner (M23.1 redesign; wired to the session in M23.3; live-mode copy corrected in
 * Project 3 — mode semantics).
 *
 * The mode toggle is the highest-consequence control in the product, and test/live confusion
 * against real money is the failure M23's risk table names. So test mode is visually
 * unmistakable.
 *
 * **Live mode is not silent, because in this deployment it also moves no real money.** The
 * original design made live mode show nothing — "the absence of a warning is the clearest
 * possible signal that this is real." That is the right call the day a real acquirer is
 * connected. It is not the right call now: PaymentFlow has no PSP integration, `providers.external`
 * is off, and live-mode authorization is a *simulated* stochastic acquirer (`DecisionEngine
 * .decideLive`). A blank bar next to live data would imply a charge that cannot happen. So live
 * mode carries one quiet, accurate line instead — neutral, not alarmed, and never styled like
 * the test banner.
 *
 * Colour is never the only channel — an icon and a sentence carry it too — and the test amber
 * comes from `--color-mode-test`, outside the status palette, so it never reads as "pending".
 */
export function ModeBanner({
  mode,
  className,
}: {
  mode: 'test' | 'live';
  className?: string | undefined;
}) {
  if (mode === 'live') {
    return (
      <div
        role="status"
        className={cn(
          'flex h-7 items-center justify-center gap-1.5 border-b border-border-subtle bg-surface-inset px-4',
          'text-label-sm font-[510] text-fg-subtle',
          className,
        )}
      >
        <Radio aria-hidden className="size-3 shrink-0" />
        <span>
          Live mode — production-style payment APIs. No external acquirer is connected in this
          deployment, so no real money moves.
        </span>
      </div>
    );
  }

  return (
    <div
      role="status"
      className={cn(
        'flex h-7 items-center justify-center gap-1.5 bg-mode-test-surface px-4',
        'text-label-sm font-[510] text-mode-test',
        className,
      )}
    >
      <FlaskConical aria-hidden className="size-3 shrink-0" />
      <span>Test data — a sandboxed acquirer. Nothing here moves real money.</span>
    </div>
  );
}
