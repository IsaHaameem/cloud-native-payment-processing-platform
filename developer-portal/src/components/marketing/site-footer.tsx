import Link from 'next/link';

import { Wordmark } from '@/components/layout/logo';
import { SITE_NAV } from '@/components/marketing/site-nav';
import { API_VERSION } from '@/generated/contract';

/**
 * The public footer (M23.2a).
 *
 * Short, because a footer of five columns each holding two real links and eight dead ones is the
 * clearest possible signal that a site is scaffolding. It carries what is true: the same sections
 * the navbar points at, the two entry points, and the API revision currently published.
 *
 * That revision is read from the generated contract, so it is the one the platform is actually
 * serving — the same guarantee the dashboard's quickstart relies on, and the reason neither can
 * quietly start advertising a date that has moved on.
 */
export function SiteFooter({ signedIn }: { signedIn: boolean }) {
  return (
    <footer className="border-t border-border-subtle">
      <div className="mx-auto flex w-full max-w-6xl flex-col gap-8 px-5 py-12 sm:px-8 md:flex-row md:items-start md:justify-between">
        <div>
          <Wordmark />
          <p className="mt-3 max-w-xs text-label text-fg-subtle">
            Payments infrastructure for developers — idempotent by default, double-entry all the way
            down.
          </p>
          <p className="mt-4 text-label-sm text-fg-faint">
            API revision <span className="tabular font-mono">{API_VERSION}</span>
          </p>
        </div>

        <div className="flex gap-12">
          <FooterColumn label="Platform">
            {SITE_NAV.map((item) => (
              <a key={item.href} href={item.href} className={linkClass}>
                {item.label}
              </a>
            ))}
          </FooterColumn>

          {/*
           * The same rule the navbar and the hero follow: a signed-in visitor is never offered
           * an account they have. Both of those links redirect for such a visitor anyway, so
           * leaving them would leave two controls that do not do what they say.
           */}
          <FooterColumn label="Account">
            {signedIn ? (
              <Link href="/dashboard" className={linkClass}>
                Dashboard
              </Link>
            ) : (
              <>
                <Link href="/signup" className={linkClass}>
                  Create account
                </Link>
                <Link href="/login" className={linkClass}>
                  Sign in
                </Link>
              </>
            )}
          </FooterColumn>
        </div>
      </div>
    </footer>
  );
}

const linkClass =
  'text-label text-fg-subtle transition-colors duration-(--duration-fast) hover:text-fg';

function FooterColumn({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-2.5">
      <p className="text-caption font-[510] tracking-[0.04em] text-fg-faint uppercase">{label}</p>
      {children}
    </div>
  );
}
