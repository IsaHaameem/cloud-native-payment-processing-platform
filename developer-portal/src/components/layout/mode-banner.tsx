import { FlaskConical } from 'lucide-react';

import { cn } from '@/lib/utils';

/**
 * The test-mode banner (M23.1 redesign; wired to the session in M23.3).
 *
 * The mode toggle is the highest-consequence control in the product: the same button that shows
 * a developer their sandbox shows a merchant their real money. M23's risk table names "mode
 * confusion causes a destructive action against live data" as the failure worth spending design
 * effort on.
 *
 * So test mode is visually unmistakable and live mode is silent. That asymmetry is the decision:
 * a banner on both would train people to ignore it, and the absence of a warning is the clearest
 * possible signal that this is real.
 *
 * Colour is never the only channel — an icon and a sentence carry it too — and the amber comes
 * from `--color-mode-test`, deliberately outside both the status palette and the brand indigo, so
 * a merchant never has to work out whether amber here means "pending".
 */
export function ModeBanner({
  mode,
  className,
}: {
  mode: 'test' | 'live';
  className?: string | undefined;
}) {
  if (mode === 'live') return null;

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
      <span>Test data — nothing here moves real money.</span>
    </div>
  );
}
