import type { Metadata } from 'next';

import { PageHeader } from '@/components/patterns/page-header';
import { requireMerchant } from '@/lib/session/require';

import { PaymentsBrowser } from './payments-browser';

export const metadata: Metadata = { title: 'Payments' };

/**
 * The payments list (M23.6).
 *
 * ── Why the rows are fetched by the client and not rendered here ──────────────────────
 *
 * This is the screen M23.3's data layer was built for, and D207 says so outright: the read route
 * exists because "§6.6 asks for a client query library with cursor pagination and refetch after a
 * mutation", and the note records that pagination was unbuildable until the first list screen
 * arrived. This is that screen.
 *
 * Server-rendering the first page and paginating client-side afterwards was the alternative. It
 * costs a second code path over the same operation — one through `callAs`, one through
 * `/api/platform/listPayments` — which must agree on filters, cursors and error shapes forever,
 * and it hands TanStack Query a first page it did not fetch and cannot refetch on the same terms.
 * The gain is one avoided skeleton on a screen whose whole interaction model is *changing the
 * query*, where a skeleton is the honest answer every time the filters move anyway.
 *
 * So this component does what only a server component can: it proves there is a session and a
 * merchant before a single row is requested.
 *
 * ── The URL carries the filters, and never the scope ──────────────────────────────────
 *
 * Filters live in the query string so a view is a link (see `lib/payments/filters.ts`). Merchant
 * and mode are not in it and must not be: both come from the sealed session, the gateway derives
 * the merchant from the JWT, and `/api/platform/[operation]` rejects `mode` as a parameter no
 * operation documents. A link therefore means "these filters, *my* data, in whichever mode I am
 * in" — which is the only thing it could safely mean when shared.
 */
export const dynamic = 'force-dynamic';

export default async function PaymentsPage() {
  // Not used for the query — the scope comes from `QueryScopeProvider` in the shell — but this is
  // what makes reaching the page without a merchant impossible, so no row is ever requested
  // by a session that has nothing to request rows for.
  await requireMerchant();

  return (
    <div>
      <PageHeader
        title="Payments"
        description="Every payment on your account, newest first. Filters are part of the address — copy the link to share exactly this view."
      />
      <PaymentsBrowser />
    </div>
  );
}
