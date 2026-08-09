import { Badge } from '@/components/ui/badge';

/**
 * A payment's status, as the platform spells it (M23.6).
 *
 * ── The seven values are the FSM's, not a design's ────────────────────────────────────
 *
 * `PaymentStatus` is `CREATED → AUTHORIZED → CAPTURED → {PARTIALLY_REFUNDED, REFUNDED}`, with
 * `FAILED` and `VOIDED` as the two ways out. There is no `PARTIALLY_CAPTURED` — capture is
 * all-or-nothing in the approved lifecycle — and this file must never imply one exists.
 *
 * ── An unknown status is shown, not swallowed ─────────────────────────────────────────
 *
 * The contract types `status` as an open string and says so explicitly: *"new values may be added
 * without a new revision, so treat an unrecognised status as one you do not handle rather than as
 * an error."* So an unfamiliar value renders as itself in the neutral tone rather than as "Unknown"
 * or as nothing at all. A merchant seeing a status the portal has not been taught yet is a screen
 * that is behind; a merchant seeing a blank cell is a screen that is wrong.
 *
 * ── Tone carries meaning that colour alone must not ───────────────────────────────────
 *
 * Every badge pairs its colour with the word, and `dot` adds a non-colour mark, so the three
 * "something is wrong" states are distinguishable without relying on hue.
 */

/** Tone per status. Absent from this map means "not a value this build knows about". */
const TONES: Record<string, 'neutral' | 'info' | 'success' | 'warning' | 'danger'> = {
  created: 'neutral',
  authorized: 'info',
  captured: 'success',
  partially_refunded: 'warning',
  refunded: 'warning',
  failed: 'danger',
  voided: 'neutral',
};

/**
 * Display text per status.
 *
 * Only `partially_refunded` genuinely needs a mapping; the rest are their own wire value
 * capitalised. Spelled out rather than computed so the two-word case is not a special branch in a
 * string transform.
 */
const LABELS: Record<string, string> = {
  created: 'Created',
  authorized: 'Authorized',
  captured: 'Captured',
  partially_refunded: 'Partly refunded',
  refunded: 'Refunded',
  failed: 'Failed',
  voided: 'Voided',
};

export function StatusBadge({ status }: { status: string | undefined }) {
  if (!status) return <span className="text-fg-faint">—</span>;

  const key = status.toLowerCase();

  return (
    <Badge tone={TONES[key] ?? 'neutral'} dot>
      {LABELS[key] ?? status}
    </Badge>
  );
}

/** Exported for the filter picker, so the two lists cannot disagree about how a status reads. */
export function statusLabel(status: string): string {
  return LABELS[status.toLowerCase()] ?? status;
}
