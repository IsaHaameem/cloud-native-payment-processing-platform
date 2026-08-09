import { AlertCircle, Check, KeyRound, ShieldCheck, Webhook } from 'lucide-react';
import type { Metadata } from 'next';
import Link from 'next/link';

import { PageHeader } from '@/components/patterns/page-header';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { formatDateTime } from '@/lib/format';
import { currentMerchant } from '@/lib/platform/current-merchant';
import { fetchCurrentUser } from '@/lib/platform/users';
import { readCsrfToken } from '@/lib/security/csrf';
import { requireMerchant } from '@/lib/session/require';

import { BusinessProfileForm, WebhookUrlForm } from './settings-forms';

export const metadata: Metadata = { title: 'Settings' };

/**
 * Account and business settings (M23.4).
 *
 * ── What is here, and what §6.1 puts on this route that is not ────────────────────────
 *
 * §6.1 lists `/settings` as "Profile, merchant, team, API version pin — M23/M24". This is the
 * M23 half, and the split is decided by the backend rather than by preference:
 *
 * - **Profile** — `GET /api/v1/users/me`. Read-only, because `UserController` exposes no update
 *   endpoint. Said on the screen rather than implied by missing buttons.
 * - **Merchant** — `GET`/`PATCH /api/v1/merchants/me` and `PATCH /me/webhook`. The editable part.
 * - **Team** — no team, membership or invitation entity exists anywhere in the platform. Not
 *   deferred by choice; there is nothing to call.
 * - **API version pin** — `pinned_api_version` is a real column and rides on the *internal*
 *   `ApiKeyVerifyResponse`, but `MerchantResponse` does not carry it, so the account plane cannot
 *   read it. Showing it needs a contract change, which M23.4 does not authorise.
 *
 * Both absences are stated on the page. A settings screen that silently lacks a section a user
 * expects reads as unfinished; one that says which milestone owns it reads as planned — and the
 * roadmap is public in this repository anyway.
 *
 * ── `requireMerchant`, so the editable half always has something to edit ──────────────
 *
 * A user with no merchant is sent to onboarding rather than shown a form whose every save would
 * 404. That is the same guard `/dashboard` uses, and it is why this page can render the business
 * section unconditionally.
 *
 * ── Never cached ──────────────────────────────────────────────────────────────────────
 *
 * Per-user, per-merchant, and it carries a CSRF token. Any one of those rules out a shared copy.
 */
export const dynamic = 'force-dynamic';

export default async function SettingsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const session = await requireMerchant();

  /*
   * `?saved=` is a flag, never a message.
   *
   * The page renders fixed copy from it and echoes nothing out of the URL — the same rule
   * `/login` follows for `?registered=1`, and for the same reason: a page that prints arbitrary
   * text from its own query string is a phishing surface with the product's domain on it. An
   * unrecognised value simply shows no banner.
   */
  const savedParam = (await searchParams).saved;
  const saved = Array.isArray(savedParam) ? savedParam[0] : savedParam;

  // Both reads in parallel: they are independent, and doing them in sequence would make the
  // slowest screen in the portal twice as slow for no reason.
  const [merchantLookup, userLookup, csrfToken] = await Promise.all([
    currentMerchant(session.accessToken),
    fetchCurrentUser(session.accessToken),
    readCsrfToken(),
  ]);

  const merchant = merchantLookup.status === 'found' ? merchantLookup.merchant : undefined;
  const user = userLookup.status === 'found' ? userLookup.user : undefined;

  return (
    <div>
      <PageHeader
        title="Settings"
        description="Your account and the business your payments belong to."
      />

      <div className="flex flex-col gap-4">
        <Section
          title="Business profile"
          description="How this account identifies itself. Changing it takes effect immediately."
          saved={saved === 'business'}
        >
          {merchant ? (
            <BusinessProfileForm
              csrfToken={csrfToken}
              businessName={merchant.businessName}
              contactEmail={merchant.contactEmail}
            />
          ) : (
            <Unavailable what="business profile" />
          )}
        </Section>

        <Section
          icon={Webhook}
          title="Callback URL"
          description="An address carried on your account for downstream services. This is not where webhook deliveries are sent — endpoints are managed separately."
          saved={saved === 'callback'}
        >
          {merchant ? (
            <WebhookUrlForm csrfToken={csrfToken} webhookUrl={merchant.webhookUrl} />
          ) : (
            <Unavailable what="callback URL" />
          )}
        </Section>

        <Section
          icon={ShieldCheck}
          title="Account"
          description="Your sign-in identity. These details are managed by the platform and are not editable here."
        >
          {user ? (
            <dl className="grid gap-4 sm:grid-cols-2">
              <Fact label="Email">
                <span className="flex flex-wrap items-center gap-2">
                  <span className="truncate">{user.email}</span>
                  <Badge tone={user.emailVerified ? 'success' : 'warning'} dot>
                    {user.emailVerified ? 'Verified' : 'Unverified'}
                  </Badge>
                </span>
              </Fact>
              <Fact label="Name">{user.fullName ?? 'Not set'}</Fact>
              <Fact label="Role">{user.roles.join(', ') || 'USER'}</Fact>
              <Fact label="Member since">
                {user.createdAt ? formatDateTime(user.createdAt) : 'Unknown'}
              </Fact>
            </dl>
          ) : (
            <Unavailable what="account details" />
          )}

          <div className="mt-5 flex flex-wrap items-center gap-3 border-t border-border-subtle pt-4">
            <Button variant="secondary" asChild>
              <Link href="/forgot-password">
                <KeyRound />
                Change password
              </Link>
            </Button>
            {/*
             * Honest about the route it takes. identity-service has no authenticated
             * change-password endpoint — only the emailed reset flow M23.2b wired up — so this
             * sends a link rather than opening a form, and says so before it is clicked.
             */}
            <p className="text-label text-fg-subtle">
              We&rsquo;ll email you a link. This signs you out everywhere.
            </p>
          </div>
        </Section>

        <Section
          title="Not here yet"
          description="Two things §6.1 puts on this screen that the platform cannot answer yet."
        >
          <ul className="flex flex-col gap-3">
            <Planned title="Team members">
              Inviting and managing teammates needs merchant-side membership, which does not exist
              in the platform yet.
            </Planned>
            <Planned title="API version pin">
              Your account is pinned to the API revision you first called, and that pin is not
              readable through the account API — so it cannot be shown or changed here.
            </Planned>
          </ul>
        </Section>
      </div>
    </div>
  );
}

function Section({
  icon: Icon,
  title,
  description,
  saved = false,
  children,
}: {
  icon?: React.ComponentType<{ className?: string; 'aria-hidden'?: boolean }> | undefined;
  title: string;
  description: string;
  /** Renders the confirmation for a save that has just completed. */
  saved?: boolean;
  children: React.ReactNode;
}) {
  return (
    <Card>
      <CardContent className="pt-5">
        <div className="mb-5 flex max-w-2xl flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
          <div>
            <p className="flex items-center gap-2 text-label font-[510] text-fg">
              {Icon ? <Icon aria-hidden className="size-3.5 text-fg-subtle" /> : null}
              {title}
            </p>
            <p className="mt-1 text-body text-pretty text-fg-subtle">{description}</p>
          </div>
          {saved ? (
            <span
              role="status"
              className="inline-flex shrink-0 items-center gap-1 text-label text-success"
            >
              <Check aria-hidden className="size-3.5" />
              Saved
            </span>
          ) : null}
        </div>
        <div className="max-w-md">{children}</div>
      </CardContent>
    </Card>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-label-sm font-[510] tracking-[0.02em] text-fg-subtle uppercase">
        {label}
      </dt>
      <dd className="mt-1 truncate text-body text-fg">{children}</dd>
    </div>
  );
}

function Planned({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <li>
      <p className="text-label font-[510] text-fg">{title}</p>
      <p className="mt-0.5 text-label text-fg-subtle">{children}</p>
    </li>
  );
}

/**
 * What a section shows when its read failed.
 *
 * Per section rather than for the whole page, because the two reads are independent: a
 * merchant-service blip must not hide the account details identity-service returned perfectly
 * well. Reloading is the only action available and is offered as one.
 */
function Unavailable({ what }: { what: string }) {
  return (
    <div className="flex items-start gap-2.5 rounded-md bg-surface-inset p-3.5 ring-hairline">
      <AlertCircle aria-hidden className="mt-0.5 size-4 shrink-0 text-warning" />
      <div>
        <p className="text-label font-[510] text-fg">Could not load your {what}</p>
        <p className="mt-0.5 text-label text-fg-subtle">
          The platform did not answer. Reload the page to try again.
        </p>
      </div>
    </div>
  );
}
