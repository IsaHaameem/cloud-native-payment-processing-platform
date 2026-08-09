/**
 * The public navigation, in one place (M23.2a).
 *
 * ── Only destinations that exist ──────────────────────────────────────────────────────
 *
 * A marketing navbar wants "Product · Developers · Docs · API reference · Pricing · Status", and
 * four of those six would be links to nothing today: `/docs/**` and `/reference/**` are M25's,
 * and there is no pricing page or status page anywhere in the plan. Rendering them would produce
 * either 404s or, worse, "coming soon" pages — the two things a developer evaluating a payments
 * API reads as *this is not finished*.
 *
 * So the public nav points at sections of the page it is on. That is a real destination, it is
 * honest, and it is what a single-page product site does anyway. Each entry is an id that exists
 * in `(marketing)/page.tsx`; the header renders nothing that is not in this list, so adding
 * `/docs` later is one line here and one route there.
 *
 * The design-system page is deliberately **not** here. It was the only link the previous landing
 * page had, and an internal component gallery is not public navigation — it is the thing a
 * portfolio reviewer should find from inside the product, not the first thing a visitor is
 * offered.
 */
export interface SiteNavItem {
  readonly label: string;
  /** An in-page anchor. Every one of these ids is rendered by the landing page. */
  readonly href: string;
}

export const SITE_NAV: readonly SiteNavItem[] = [
  { label: 'Platform', href: '#platform' },
  { label: 'Lifecycle', href: '#lifecycle' },
  { label: 'Developers', href: '#developers' },
  { label: 'Reliability', href: '#reliability' },
];
