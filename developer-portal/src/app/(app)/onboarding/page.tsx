import type { Metadata } from 'next';
import { redirect } from 'next/navigation';

import { EmptyState } from '@/components/patterns/empty-state';
import { Button } from '@/components/ui/button';
import { requireSession } from '@/lib/session/require';

export const metadata: Metadata = { title: 'Onboarding' };

/**
 * Where a signed-in user with no merchant lands (M23.2).
 *
 * ── Why this exists in an authentication milestone ────────────────────────────────────
 *
 * `requireMerchant` redirects here, so without it the guard points at a 404 and the merchant-less
 * state — which is the ordinary state of every freshly verified account — has no destination.
 * The routing is authentication's business; what the page eventually *does* is not.
 *
 * ── What it deliberately does not do ──────────────────────────────────────────────────
 *
 * It does not onboard anyone. `POST /api/v1/merchants` exists and is one call away, but a
 * merchant-creation form is a screen with its own validation, error states and success path, and
 * that belongs to the milestone that owns merchant management. Building it here would be the
 * scope creep this milestone's boundary explicitly rules out.
 *
 * So it states the situation honestly rather than pretending to be finished. A user who reaches
 * it is correctly authenticated; they simply have nowhere to act yet.
 *
 * ── The reverse guard ─────────────────────────────────────────────────────────────────
 *
 * A user who *does* have a merchant is sent away. Without this, the two guards can bounce a user
 * between onboarding and the dashboard, which is the classic redirect loop.
 */
export default async function OnboardingPage() {
  const session = await requireSession();
  if (session.merchantId !== undefined) {
    redirect('/foundation');
  }

  return (
    <div className="mx-auto max-w-xl py-10">
      <EmptyState
        title="No merchant account yet"
        description="Your sign-in worked — this account just is not attached to a merchant, so there is no data to show. Creating one arrives with the merchant screens; until then the platform's API can do it directly."
        action={
          <Button variant="secondary" asChild>
            <a href="https://github.com/" target="_blank" rel="noreferrer noopener">
              Read the onboarding guide
            </a>
          </Button>
        }
      />
    </div>
  );
}
