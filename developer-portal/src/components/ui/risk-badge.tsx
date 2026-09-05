import { Badge, type BadgeProps } from '@/components/ui/badge';

/**
 * The risk class of an agent tool (frontend build).
 *
 * `frontend_Design.md §17.2`: the agent has seven tools, each with a fixed risk class —
 * READ / COMMERCE / PAYMENT / REFUND — and the class is what governs which policy rules apply.
 * The tone mapping is the same one `§8.6` gives for the agentic surface: read is inert
 * (`neutral`), commerce is `info`, payment is `success` (it moves money in, capped and logged),
 * refund is `warning` (money out, approval above the threshold).
 */
export type RiskClass = 'READ' | 'COMMERCE' | 'PAYMENT' | 'REFUND';

const TONE: Record<RiskClass, NonNullable<BadgeProps['tone']>> = {
  READ: 'neutral',
  COMMERCE: 'info',
  PAYMENT: 'success',
  REFUND: 'warning',
};

export function RiskBadge({
  risk,
  className,
}: {
  risk: RiskClass;
  className?: string | undefined;
}) {
  return (
    <Badge tone={TONE[risk]} className={className}>
      <span className="font-mono tracking-[0.04em]">{risk}</span>
    </Badge>
  );
}
