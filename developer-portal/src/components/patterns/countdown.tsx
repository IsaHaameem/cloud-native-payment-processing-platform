'use client';

import * as React from 'react';

import { Badge } from '@/components/ui/badge';

/**
 * A live "expires in …" countdown (frontend build).
 *
 * `frontend_Design.md §19.1`: an approval expires at its TTL whether or not it was granted, so
 * the queue shows a countdown; under five minutes it turns `warning`, and at zero it reads
 * "Expired" and the row's actions disable. This renders only the badge — the parent decides what
 * to do at expiry, via `onExpire`.
 *
 * The tick is once per second and cleared on unmount. `Date.now()` against a fixed target, so a
 * backgrounded tab that missed ticks still shows the right value on return.
 */
export function Countdown({
  expiresAt,
  onExpire,
}: {
  /** ISO timestamp. */
  expiresAt: string;
  onExpire?: () => void;
}) {
  const target = React.useMemo(() => new Date(expiresAt).getTime(), [expiresAt]);
  const [now, setNow] = React.useState(() => Date.now());
  const firedRef = React.useRef(false);

  React.useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  const remaining = target - now;

  React.useEffect(() => {
    if (remaining <= 0 && !firedRef.current) {
      firedRef.current = true;
      onExpire?.();
    }
  }, [remaining, onExpire]);

  if (Number.isNaN(target)) {
    return <Badge tone="neutral">no expiry</Badge>;
  }

  if (remaining <= 0) {
    return (
      <Badge tone="danger" dot>
        Expired
      </Badge>
    );
  }

  const totalSeconds = Math.floor(remaining / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  const label =
    minutes >= 60
      ? `${Math.floor(minutes / 60)}h ${minutes % 60}m`
      : `${minutes}m ${String(seconds).padStart(2, '0')}s`;

  return (
    <Badge tone={remaining < 5 * 60_000 ? 'warning' : 'neutral'} dot={remaining < 5 * 60_000}>
      <span className="tabular">expires in {label}</span>
    </Badge>
  );
}
