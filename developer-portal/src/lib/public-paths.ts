/**
 * The public marketing routes, in a dependency-free module so `middleware.ts` can import the
 * list without pulling the icon library that `nav-items.ts` brings in.
 *
 * `middleware.ts` matches everything and fails closed, so a marketing page that is not named
 * here would redirect an anonymous visitor to `/login`. Adding a public page means adding it
 * to this list.
 */
export const PUBLIC_MARKETING_PATHS = [
  '/platform',
  '/agentic-commerce',
  '/developers',
  '/docs',
  '/pricing',
  '/security',
  '/contact',
] as const;
