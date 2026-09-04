import { AlertCircle, KeyRound } from 'lucide-react';
import type { Metadata } from 'next';

import { KeyPlacement } from '@/components/integration/key-placement';
import { PageHeader } from '@/components/patterns/page-header';
import { EmptyState } from '@/components/patterns/empty-state';
import { isLive, keyStatus } from '@/lib/api-keys/status';
import { listApiKeys } from '@/lib/platform/api-keys';
import { readCsrfToken } from '@/lib/security/csrf';
import { requireMerchant } from '@/lib/session/require';

import { CreateKeyButton, KeyRow, LostSecretNotice } from './key-list';

export const metadata: Metadata = { title: 'API keys' };

/**
 * API key management (M23.5).
 *
 * ── Scoped to the session's mode, and the mode is not a filter on this page ───────────
 *
 * merchant-service returns every key a merchant has, both modes together, and the screen shows
 * one mode at a time — the one the session is in. That is not a local preference: mode is session
 * state that selects the data plane every request reads (D184), and a page-local mode filter would
 * be a second control for the one piece of state the whole shell is already scoped by, able to
 * disagree with the header while a user looked at both.
 *
 * The consequence is stated on the page rather than left to be discovered: when keys exist in the
 * other mode, it says how many and points at the toggle that reveals them. A developer who cannot
 * find the live key they created is the failure this sentence exists to prevent.
 *
 * Mode is a display scope here, not an isolation boundary. The boundary is merchant ownership, and
 * merchant-service enforces it from the JWT subject on every one of these calls.
 *
 * ── Never cached, and the reason is stronger than usual ───────────────────────────────
 *
 * Per-user and per-merchant, like every other authenticated page — but also a credential
 * inventory, where a stale render means showing a key as live after it was revoked. `no-store` is
 * set on the fetch as well, so nothing between here and the gateway can hold it either.
 */
export const dynamic = 'force-dynamic';

export default async function ApiKeysPage() {
  const session = await requireMerchant();

  const [listing, csrfToken] = await Promise.all([
    listApiKeys(session.accessToken),
    readCsrfToken(),
  ]);

  if (listing.status !== 'found') {
    return (
      <div>
        <Header mode={session.mode} />
        <div className="flex items-start gap-2.5 rounded-md bg-surface-inset p-4 ring-hairline">
          <AlertCircle aria-hidden className="mt-0.5 size-4 shrink-0 text-warning" />
          <div>
            <p className="text-label font-[510] text-fg">Could not load your API keys</p>
            <p className="mt-0.5 text-label text-fg-subtle">
              The platform did not answer. Reload the page to try again — no key was changed.
            </p>
          </div>
        </div>
      </div>
    );
  }

  const inThisMode = listing.keys.filter((key) => key.mode === session.mode);
  const elsewhere = listing.keys.length - inThisMode.length;

  /*
   * Live keys first, dead ones after.
   *
   * The platform orders by creation date, which puts a key revoked this morning above the one
   * that is actually authenticating traffic. Within each group the platform's order is kept, so
   * "newest first" still holds where it means something.
   */
  const now = new Date();
  const ordered = [...inThisMode].sort((a, b) => {
    const live = Number(isLive(keyStatus(b, now))) - Number(isLive(keyStatus(a, now)));
    return live;
  });

  const usable = inThisMode.filter((key) => isLive(keyStatus(key, now)));
  const neverUsed = usable.length > 0 && usable.every((key) => key.lastUsedAt === undefined);

  return (
    <div>
      <Header mode={session.mode} csrfToken={csrfToken} showAction={ordered.length > 0} />

      <KeyPlacement mode={session.mode} />

      {/*
       * Shown only when the evidence fits: keys exist, and not one of the live ones has ever
       * authenticated a request. That is the signature of an account still holding the starter set
       * — and of one that has just made its first key, where the notice is still true and still
       * worth reading.
       */}
      {neverUsed ? <LostSecretNotice /> : null}

      {ordered.length === 0 ? (
        <EmptyState
          icon={<KeyRound aria-hidden className="size-5 text-accent" />}
          title={`No ${session.mode} keys yet`}
          description={
            elsewhere > 0
              ? `You have ${elsewhere} key${elsewhere === 1 ? '' : 's'} in ${session.mode === 'test' ? 'live' : 'test'} mode. Create one here, or switch mode in the header to see them.`
              : 'Create a key to start calling the API. The secret is shown once, at the moment it is created.'
          }
          action={<CreateKeyButton csrfToken={csrfToken} mode={session.mode} />}
        />
      ) : (
        <ul className="flex flex-col gap-2.5">
          {ordered.map((key) => (
            <li key={key.id}>
              <KeyRow apiKey={key} csrfToken={csrfToken} />
            </li>
          ))}
        </ul>
      )}

      {ordered.length > 0 && elsewhere > 0 ? (
        <p className="mt-5 text-label text-fg-subtle">
          {elsewhere} more key{elsewhere === 1 ? '' : 's'} exist
          {elsewhere === 1 ? 's' : ''} in {session.mode === 'test' ? 'live' : 'test'} mode. Switch
          mode in the header to manage {elsewhere === 1 ? 'it' : 'them'}.
        </p>
      ) : null}
    </div>
  );
}

function Header({
  mode,
  csrfToken,
  showAction = false,
}: {
  mode: 'test' | 'live';
  csrfToken?: string;
  showAction?: boolean;
}) {
  return (
    <PageHeader
      title="API keys"
      description={`Credentials your server uses to call the PaymentFlow API. You are viewing ${mode} mode.`}
      actions={
        showAction && csrfToken ? <CreateKeyButton csrfToken={csrfToken} mode={mode} /> : undefined
      }
    />
  );
}
