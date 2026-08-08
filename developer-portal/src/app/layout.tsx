import type { Metadata, Viewport } from 'next';
import { Inter, JetBrains_Mono } from 'next/font/google';

import { Providers } from '@/providers';

import './globals.css';

/**
 * The document (M23.1, redesigned to the Linear system).
 *
 * ── The typeface is the design ────────────────────────────────────────────────────────
 *
 * The reference is unambiguous: "Typography is exclusively Inter Variable". Loaded through
 * `next/font`, which self-hosts it at build time — so there is no request to a third-party
 * origin at runtime, which is what lets the CSP keep `font-src 'self'` and `connect-src 'self'`
 * rather than widening both for a stylesheet.
 *
 * The variable axis matters. The system's signature weight is **510** — between regular and
 * medium — and it only exists if the variable font is loaded rather than static instances,
 * which is why no `weight` is pinned here.
 *
 * `Berkeley Mono` is commercially licensed and unavailable, so JetBrains Mono takes the
 * `code-inline` role: the closest freely-available face in the same register, and the
 * substitution is recorded rather than silently made.
 */
const sans = Inter({
  subsets: ['latin'],
  variable: '--font-inter',
  display: 'swap',
  axes: ['opsz'],
});

const mono = JetBrains_Mono({
  subsets: ['latin'],
  variable: '--font-mono-stack',
  display: 'swap',
});

export const metadata: Metadata = {
  title: {
    default: 'PaymentFlow',
    template: '%s · PaymentFlow',
  },
  description: 'The PaymentFlow developer portal — payments, keys, webhooks and logs.',
  robots: { index: false, follow: false },
};

export const viewport: Viewport = {
  themeColor: [
    { media: '(prefers-color-scheme: dark)', color: '#08090a' },
    { media: '(prefers-color-scheme: light)', color: '#f7f8f8' },
  ],
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    /*
     * `suppressHydrationWarning` is required and is not a workaround: next-themes writes the
     * theme class onto <html> before React hydrates, so the server's markup and the client's
     * first pass genuinely differ on this one element. Suppressing it here is narrower than the
     * alternative, which is rendering the whole app only after mount.
     */
    <html lang="en" className={`${sans.variable} ${mono.variable}`} suppressHydrationWarning>
      <body className="min-h-dvh bg-canvas text-fg antialiased">
        <a
          href="#main"
          className="sr-only rounded-md bg-accent px-3 py-2 text-label font-[510] text-fg-on-accent focus:not-sr-only focus:absolute focus:top-3 focus:left-3 focus:z-50"
        >
          Skip to content
        </a>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
