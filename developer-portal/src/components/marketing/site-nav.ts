/**
 * The public navigation now lives in `@/components/layout/nav-items` alongside the dashboard IA,
 * so the two cannot drift and `middleware.ts` can read the public path list from the same place.
 * This module is kept as a re-export for existing import sites.
 */
export { SITE_NAV, type SiteNavItem } from '@/components/layout/nav-items';
