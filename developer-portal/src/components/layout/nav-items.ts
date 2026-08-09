import {
  Activity,
  BarChart3,
  CreditCard,
  KeyRound,
  LayoutDashboard,
  Receipt,
  ScrollText,
  Settings,
  Wallet,
  Webhook,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

/**
 * The information architecture, in one place (M23.1).
 *
 * ── Why every M24 destination is already listed ───────────────────────────────────────
 *
 * §6.1 fixes the IA for both portal milestones, and a navigation that grows an item at a time
 * reshuffles under the user twice. Listing the full set now means the sidebar's shape is
 * decided once — and `enabled: false` is honest about which entries lead somewhere: they render
 * as visibly unavailable rather than as links that 404, which is what a hidden-until-built
 * approach would produce the moment someone typed the URL.
 *
 * The alternative, showing only what exists, was rejected for the same reason the route table
 * in the M23 specification exists: a surface that appears late looks like a feature that was
 * missing, not one that was scheduled.
 */
export interface NavItem {
  readonly label: string;
  readonly href: string;
  readonly icon: LucideIcon;
  /** False for a destination a later milestone builds. Rendered, unclickable, marked. */
  readonly enabled: boolean;
  /** The milestone that turns it on — shown in the tooltip so "why is this greyed out" has an answer. */
  readonly milestone: string;
}

export interface NavSection {
  readonly label: string;
  readonly items: readonly NavItem[];
}

export const NAV_SECTIONS: readonly NavSection[] = [
  {
    label: 'Business',
    items: [
      // The first entry to be turned on. M23.2a built `/dashboard` as the authenticated entry
      // point the whole sign-up flow leads to; the figures §6.2 specifies for it arrive in M23.8,
      // which is a change to what the page shows rather than to whether it exists.
      {
        label: 'Overview',
        href: '/dashboard',
        icon: LayoutDashboard,
        enabled: true,
        milestone: 'M23.8',
      },
      {
        label: 'Payments',
        href: '/payments',
        icon: CreditCard,
        enabled: false,
        milestone: 'M23.6',
      },
      { label: 'Refunds', href: '/refunds', icon: Receipt, enabled: false, milestone: 'M23.7' },
      { label: 'Balance', href: '/balance', icon: Wallet, enabled: false, milestone: 'M24' },
    ],
  },
  {
    label: 'Developers',
    items: [
      {
        label: 'API keys',
        href: '/developers/api-keys',
        icon: KeyRound,
        enabled: false,
        milestone: 'M23.5',
      },
      {
        label: 'Webhooks',
        href: '/developers/webhooks',
        icon: Webhook,
        enabled: false,
        milestone: 'M24',
      },
      {
        label: 'Request logs',
        href: '/developers/logs',
        icon: ScrollText,
        enabled: false,
        milestone: 'M24',
      },
      {
        label: 'Events',
        href: '/developers/events',
        icon: Activity,
        enabled: false,
        milestone: 'M24',
      },
    ],
  },
  {
    label: 'Insights',
    items: [
      { label: 'Analytics', href: '/analytics', icon: BarChart3, enabled: false, milestone: 'M24' },
      { label: 'Settings', href: '/settings', icon: Settings, enabled: false, milestone: 'M23.4' },
    ],
  },
];
