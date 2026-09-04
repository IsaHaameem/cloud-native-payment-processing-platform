'use client';

import { cn } from '@/lib/utils';

/**
 * A four-segment password strength hint (frontend build).
 *
 * Reference: `Sign Up.dc.html`. Purely advisory — the field stays uncontrolled so the Server
 * Action still reads it from `FormData`, and the real bound (8–72 chars) is enforced by the
 * action regardless of what this shows. The score is a rough length-and-variety heuristic, not a
 * gate.
 */
export function passwordScore(value: string): number {
  let score = 0;
  if (value.length >= 8) score += 1;
  if (value.length >= 12) score += 1;
  if (/[^A-Za-z0-9]/.test(value) || (/[A-Z]/.test(value) && /[0-9]/.test(value))) score += 1;
  if (value.length >= 16) score += 1;
  return score;
}

const FILL = ['bg-danger', 'bg-warning', 'bg-warning', 'bg-success'];
const WORD = ['Weak', 'Fair', 'Good', 'Strong'];

export function PasswordStrength({ value }: { value: string }) {
  const score = passwordScore(value);
  if (value.length === 0) return null;

  return (
    <div className="mt-1.5 space-y-1" aria-hidden>
      <div className="flex gap-1">
        {[0, 1, 2, 3].map((i) => (
          <span
            key={i}
            className={cn(
              'h-0.5 flex-1 rounded-full transition-colors duration-(--duration-fast)',
              i < score ? FILL[score - 1] : 'bg-border',
            )}
          />
        ))}
      </div>
      <p className="text-label-sm text-fg-subtle">{WORD[Math.max(0, score - 1)]}</p>
    </div>
  );
}
