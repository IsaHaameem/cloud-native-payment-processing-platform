import Link from 'next/link';

import { Wordmark } from '@/components/layout/logo';
import { API_VERSION } from '@/generated/contract';

/**
 * The public footer (M23.2a; expanded with the marketing site).
 *
 * Three columns, and every link goes somewhere real. The reference's Landing draft shows five
 * columns, but several of its entries ("Test instruments", "Status vocabulary", "Terms") have no
 * page behind them — and a footer of real links beside dead ones is the clearest possible signal
 * that a site is scaffolding. Anchor links into `/docs` cover the reference material without
 * inventing routes.
 *
 * The API revision is read from the generated contract, so it is the one the platform is
 * actually serving — the same guarantee the dashboard's quickstart relies on.
 */
export function SiteFooter({ signedIn }: { signedIn: boolean }) {
  return (
    <footer className="border-t border-border-subtle">
      <div className="mx-auto flex w-full max-w-6xl flex-col gap-10 px-5 py-14 sm:px-8 md:flex-row md:items-start md:justify-between">
        <div className="max-w-xs">
          <Wordmark />
          <p className="mt-3 text-label text-fg-subtle">
            Distributed payment orchestration and agentic commerce infrastructure — idempotent by
            default, double-entry all the way down.
          </p>
          <p className="mt-4 text-label-sm text-fg-faint">
            API revision <span className="tabular font-mono">{API_VERSION}</span>
          </p>
        </div>

        <div className="grid grid-cols-2 gap-x-10 gap-y-8 sm:grid-cols-3">
          <FooterColumn label="Product">
            <FooterLink href="/platform">Platform</FooterLink>
            <FooterLink href="/agentic-commerce">Agentic Commerce</FooterLink>
            <FooterLink href="/pricing">Pricing</FooterLink>
            <FooterLink href="/security">Security</FooterLink>
          </FooterColumn>

          <FooterColumn label="Developers">
            <FooterLink href="/developers">Developer platform</FooterLink>
            <FooterLink href="/docs">Documentation</FooterLink>
            <FooterLink href="/docs#reference">API reference</FooterLink>
            <FooterLink href="/docs#sandbox">Sandbox</FooterLink>
          </FooterColumn>

          <FooterColumn label="Company">
            <FooterLink href="/contact">Contact sales</FooterLink>
            {signedIn ? (
              <FooterLink href="/dashboard">Dashboard</FooterLink>
            ) : (
              <>
                <FooterLink href="/signup">Get started</FooterLink>
                <FooterLink href="/login">Sign in</FooterLink>
              </>
            )}
          </FooterColumn>
        </div>
      </div>

      <div className="border-t border-border-subtle">
        <div className="mx-auto flex w-full max-w-6xl flex-wrap items-center justify-between gap-2 px-5 py-5 text-label-sm text-fg-subtle sm:px-8">
          <span>© {new Date().getFullYear()} PaymentFlow</span>
          <span className="tabular font-mono">PaymentFlow-Version: {API_VERSION}</span>
        </div>
      </div>
    </footer>
  );
}

function FooterLink({ href, children }: { href: string; children: React.ReactNode }) {
  return (
    <Link
      href={href}
      className="text-label text-fg-subtle transition-colors duration-(--duration-fast) hover:text-fg"
    >
      {children}
    </Link>
  );
}

function FooterColumn({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-2.5">
      <p className="text-caption font-[510] tracking-[0.04em] text-fg-faint uppercase">{label}</p>
      {children}
    </div>
  );
}
