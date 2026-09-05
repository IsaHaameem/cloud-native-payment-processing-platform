import {
  Activity,
  BarChart3,
  Boxes,
  CreditCard,
  FileClock,
  FlaskConical,
  GitBranch,
  KeyRound,
  LayoutDashboard,
  ListChecks,
  MessagesSquare,
  Package,
  Receipt,
  Rocket,
  Settings,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  Wallet,
  Webhook,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

import { PUBLIC_MARKETING_PATHS } from '@/lib/public-paths';

/**
 * The information architecture, in one place (M23.1; expanded for the full frontend build).
 *
 * ── Why the whole tree is listed now ─────────────────────────────────────────────────
 *
 * `frontend_Design.md §5` fixes the IA for the entire product — five groups, ~30 destinations —
 * and a navigation that grows an item at a time reshuffles under the user. Listing the full set
 * once means the sidebar's shape is decided once.
 *
 * `enabled: false` is honest about which entries lead somewhere yet: they render as visibly
 * unavailable with a tooltip, rather than as links that 404. As each build phase lands its
 * routes, the corresponding entries flip to `enabled: true`.
 *
 * ── `gap` is a different thing from `enabled` ────────────────────────────────────────
 *
 * A `gap` route is fully built and reachable — it just has no backend API behind part of it, so
 * the page carries the project's `BackendGapNotice` treatment. The nav entry is a normal link;
 * the honesty lives on the page, not in a greyed-out sidebar row. See `frontend_Design.md §38`
 * for the G-numbers.
 */
export interface NavItem {
  readonly label: string;
  readonly href: string;
  readonly icon: LucideIcon;
  /** False for a destination not yet built. Rendered, unclickable, marked with `note`. */
  readonly enabled: boolean;
  /** Shown in the tooltip of a disabled item, or as context for a backend-gap route. */
  readonly note?: string;
  /** A live count badge fed by a client island: the pending-approvals or failing-webhooks number. */
  readonly badge?: 'approvals' | 'webhooks';
}

export interface NavSection {
  /** Empty string renders the group with a hairline instead of a heading (the lead group). */
  readonly label: string;
  readonly items: readonly NavItem[];
}

export const NAV_SECTIONS: readonly NavSection[] = [
  {
    label: '',
    items: [{ label: 'Overview', href: '/dashboard', icon: LayoutDashboard, enabled: true }],
  },
  {
    label: 'Payments',
    items: [
      { label: 'Payments', href: '/payments', icon: CreditCard, enabled: true },
      { label: 'Refunds', href: '/refunds', icon: Receipt, enabled: true },
      { label: 'Balance', href: '/balance', icon: Wallet, enabled: true },
    ],
  },
  {
    label: 'Agentic commerce',
    items: [
      { label: 'Overview', href: '/agentic', icon: LayoutDashboard, enabled: true },
      {
        label: 'Catalog',
        href: '/agentic/catalog',
        icon: Package,
        enabled: true,
        note: 'No catalog HTTP API yet (gap G-2)',
      },
      {
        label: 'Agent',
        href: '/agentic/agent',
        icon: SlidersHorizontal,
        enabled: true,
        note: 'Config is server-side only (gap G-3)',
      },
      {
        label: 'Policies',
        href: '/agentic/policies',
        icon: ShieldCheck,
        enabled: true,
        note: 'Read-only from committed config (gap G-3)',
      },
      {
        label: 'Approvals',
        href: '/agentic/approvals',
        icon: ListChecks,
        enabled: true,
        badge: 'approvals',
      },
      {
        label: 'Conversations',
        href: '/agentic/conversations',
        icon: MessagesSquare,
        enabled: true,
        note: 'No conversation list endpoint (gap G-4)',
      },
      { label: 'Actions', href: '/agentic/actions', icon: GitBranch, enabled: true },
      {
        label: 'Checkouts',
        href: '/agentic/checkouts',
        icon: Boxes,
        enabled: true,
        note: 'No checkout controller (gap G-2)',
      },
    ],
  },
  {
    label: 'Integration',
    items: [
      { label: 'Overview', href: '/developers/overview', icon: LayoutDashboard, enabled: true },
      { label: 'Quickstart', href: '/developers/quickstart', icon: Rocket, enabled: true },
      { label: 'API keys', href: '/developers/api-keys', icon: KeyRound, enabled: true },
      { label: 'AI integration', href: '/developers/ai', icon: Sparkles, enabled: true },
      { label: 'SDKs', href: '/developers/sdks', icon: Boxes, enabled: true },
      {
        label: 'Webhooks',
        href: '/developers/webhooks',
        icon: Webhook,
        enabled: true,
        badge: 'webhooks',
      },
      { label: 'Events', href: '/developers/events', icon: Activity, enabled: true },
      { label: 'Request logs', href: '/developers/logs', icon: FileClock, enabled: true },
      { label: 'Sandbox', href: '/developers/sandbox', icon: FlaskConical, enabled: true },
    ],
  },
  {
    label: 'Insights',
    items: [
      { label: 'Analytics', href: '/analytics', icon: BarChart3, enabled: true },
      { label: 'Settings', href: '/settings', icon: Settings, enabled: true },
    ],
  },
];

/**
 * The public marketing routes, shared by the site header and footer so the two cannot drift.
 * Every one is a real page under the `(marketing)` route group.
 */
export interface SiteNavItem {
  readonly label: string;
  readonly href: string;
}

export const SITE_NAV: readonly SiteNavItem[] = [
  { label: 'Platform', href: '/platform' },
  { label: 'Agentic Commerce', href: '/agentic-commerce' },
  { label: 'Developers', href: '/developers' },
  { label: 'Pricing', href: '/pricing' },
  { label: 'Security', href: '/security' },
];

export { PUBLIC_MARKETING_PATHS };
