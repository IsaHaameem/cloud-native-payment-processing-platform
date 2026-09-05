import { Badge, type BadgeProps } from '@/components/ui/badge';

type Tone = NonNullable<BadgeProps['tone']>;

/**
 * The domain status vocabularies, mapped to badge tones (frontend build).
 *
 * `frontend_Design.md §8.6` gives the binding for every status the product shows: payment, refund,
 * webhook delivery, policy decision, approval, agent action, action step, checkout and API key.
 * Each family lives here so a status reads identically wherever it appears — the list page, the
 * detail page, the docs vocabulary, the command palette.
 *
 * An unrecognised value renders as itself in the `neutral` tone. The public contract types most
 * of these as open strings ("treat an unrecognised status as one you do not handle"), so a value
 * the build has not been taught yet is shown, not swallowed.
 */
const FAMILIES: Record<string, Record<string, Tone>> = {
  payment: {
    created: 'neutral',
    authorized: 'info',
    captured: 'success',
    partially_refunded: 'warning',
    refunded: 'success',
    failed: 'danger',
    voided: 'neutral',
  },
  refund: { succeeded: 'success', pending: 'warning', failed: 'danger' },
  delivery: {
    delivered: 'success',
    pending: 'warning',
    retrying: 'warning',
    failed: 'danger',
  },
  policy: { permit: 'success', requires_approval: 'warning', refuse: 'danger' },
  approval: {
    pending: 'warning',
    approved: 'success',
    consumed: 'info',
    denied: 'danger',
    expired: 'danger',
  },
  action: {
    executed: 'success',
    executing: 'info',
    validated: 'info',
    proposed: 'info',
    approval_required: 'warning',
    refused: 'danger',
    failed: 'danger',
  },
  step: {
    succeeded: 'success',
    replayed: 'info',
    in_flight: 'warning',
    failed: 'danger',
    not_attempted: 'neutral',
  },
  checkout: {
    paid: 'success',
    open: 'info',
    locked: 'info',
    cancelled: 'neutral',
    expired: 'neutral',
  },
  key: { active: 'success', retiring: 'warning', revoked: 'danger', expired: 'neutral' },
  event: {
    'payment.captured': 'success',
    'payment.refunded': 'success',
    'payment.authorized': 'info',
    'payment.created': 'neutral',
    'payment.partially_refunded': 'warning',
    'payment.failed': 'danger',
    'payment.voided': 'neutral',
  },
};

function humanize(value: string): string {
  return value.replace(/[._]/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
}

export function StatusPill({
  status,
  family,
  label,
  dot = true,
  className,
}: {
  status: string | undefined;
  family: keyof typeof FAMILIES;
  /** Override the displayed text; defaults to a humanised form of `status`. */
  label?: string | undefined;
  dot?: boolean;
  className?: string | undefined;
}) {
  if (!status) return <span className="text-fg-faint">—</span>;
  const key = status.toLowerCase();
  const tone = FAMILIES[family]?.[key] ?? 'neutral';
  return (
    <Badge tone={tone} dot={dot} className={className}>
      {label ?? humanize(status)}
    </Badge>
  );
}

/** The tone alone, for callers that render their own chip (e.g. a timeline dot). */
export function statusTone(family: keyof typeof FAMILIES, status: string | undefined): Tone {
  if (!status) return 'neutral';
  return FAMILIES[family]?.[status.toLowerCase()] ?? 'neutral';
}
