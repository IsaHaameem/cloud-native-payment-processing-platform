'use client';

import { AnimatePresence, motion } from 'framer-motion';
import { AlertTriangle, Check, Copy, Info, KeyRound, Plus, RefreshCw, Trash2 } from 'lucide-react';
import { useActionState, useEffect, useId, useState } from 'react';
import { useFormStatus } from 'react-dom';

import { FormAlert } from '@/components/auth/form-alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { type KeyStatus, isLive, keyStatus, typeLabel } from '@/lib/api-keys/status';
import { formatDateTime, formatRelativeTime } from '@/lib/format';
import type { ApiKeySummary, IssuedApiKey } from '@/lib/platform/api-keys';
import { CSRF_FIELD } from '@/lib/security/csrf-field';

import { type KeyActionState, createKeyAction, revokeKeyAction, rotateKeyAction } from './actions';

/**
 * The API-keys screen's interactive half (M23.5).
 *
 * ── The secret's entire life, in one component tree ───────────────────────────────────
 *
 * `RevealDialog` receives the raw key from an action result, renders it, and is unmounted when
 * acknowledged — at which point the value is gone from the page for good. It is deliberately not
 * lifted into a context, a store, or a parent that outlives the dialog: the shorter the thing that
 * holds it, the smaller the set of code that could ever leak it.
 *
 * Acknowledging reloads the document rather than closing the dialog in place. The list has to
 * change — a new key appeared, or a rotated one started dying — and a server render is the only
 * thing that can show that truthfully. It also guarantees the reveal is over: after the reload,
 * nothing on the client has ever seen the secret.
 *
 * ── Why the reveal cannot be dismissed by clicking away ───────────────────────────────
 *
 * `DialogContent` here refuses outside clicks and Escape while a secret is on screen. Everywhere
 * else in the portal that would be hostile; here the value is unrecoverable, and a stray click
 * that costs a developer their only copy of a credential is a worse outcome than a dialog that
 * insists on an explicit button. Escape closes it again the moment the reveal is acknowledged.
 */

const IDLE: KeyActionState = {
  error: undefined,
  field: undefined,
  issued: undefined,
  done: undefined,
};

const SCOPES: readonly { value: string; label: string; hint: string }[] = [
  { value: '*', label: 'Full access', hint: 'Everything this key’s mode can reach' },
  { value: 'payments:read', label: 'Read payments', hint: 'List and fetch payments' },
  { value: 'payments:write', label: 'Write payments', hint: 'Create, capture and void' },
  { value: 'refunds:write', label: 'Issue refunds', hint: 'Refund a captured payment' },
];

const STATUS_TONE: Record<KeyStatus, 'success' | 'warning' | 'danger' | 'neutral'> = {
  active: 'success',
  retiring: 'warning',
  revoked: 'danger',
  expired: 'neutral',
};

const STATUS_LABEL: Record<KeyStatus, string> = {
  active: 'Active',
  retiring: 'Retiring',
  revoked: 'Revoked',
  expired: 'Expired',
};

/* ── The row ───────────────────────────────────────────────────────────────────────── */

export function KeyRow({ apiKey, csrfToken }: { apiKey: ApiKeySummary; csrfToken: string }) {
  const status = keyStatus(apiKey);
  const dead = !isLive(status);

  return (
    <motion.div
      initial={{ opacity: 0, y: 4 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.18, ease: [0.25, 0.1, 0.25, 1] }}
      className={`gpu rounded-lg bg-surface p-4 ring-hairline ${dead ? 'opacity-60' : ''}`}
    >
      <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <p className="truncate text-label font-[510] text-fg">{apiKey.name}</p>
            <Badge tone={STATUS_TONE[status]} dot>
              {STATUS_LABEL[status]}
            </Badge>
            <Badge tone="outline">{typeLabel(apiKey.type)}</Badge>
          </div>

          {/*
           * The prefix is monospace and selectable — it is an identifier a developer will compare
           * against a log line or an environment variable, and the ellipsis makes clear that what
           * is shown is a fragment rather than a truncated whole.
           */}
          <p className="mt-1.5 font-mono text-label-sm text-fg-subtle">
            {apiKey.keyPrefix}
            <span aria-hidden>{'…'}</span>
            <span className="sr-only">
              {' '}
              (the rest of this key is not stored and cannot be shown)
            </span>
          </p>

          <dl className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-label-sm text-fg-subtle">
            <Fact label="Created">{formatDateTime(apiKey.createdAt)}</Fact>
            <Fact label="Last used">
              {apiKey.lastUsedAt ? formatRelativeTime(apiKey.lastUsedAt) : 'Never'}
            </Fact>
            <Fact label="Permissions">
              {apiKey.scopes.length > 0 ? apiKey.scopes.join(', ') : 'None'}
            </Fact>
          </dl>

          {status === 'retiring' && apiKey.graceExpiresAt ? (
            <p className="mt-3 flex items-start gap-1.5 text-label-sm text-warning">
              <AlertTriangle aria-hidden className="mt-0.5 size-3.5 shrink-0" />
              <span>
                Rotated out. This key stops working {formatRelativeTime(apiKey.graceExpiresAt)} —{' '}
                {formatDateTime(apiKey.graceExpiresAt)}. Deploy the replacement before then.
              </span>
            </p>
          ) : null}

          {status === 'revoked' && apiKey.revokedAt ? (
            <p className="mt-3 text-label-sm text-fg-subtle">
              Revoked {formatRelativeTime(apiKey.revokedAt)}. Kept here as a record; it cannot be
              restored.
            </p>
          ) : null}
        </div>

        {/* A dead key offers no actions at all, rather than disabled ones that invite a click. */}
        {dead ? null : (
          <div className="flex shrink-0 items-center gap-2">
            <RotateKeyButton apiKey={apiKey} csrfToken={csrfToken} />
            <RevokeKeyButton apiKey={apiKey} csrfToken={csrfToken} />
          </div>
        )}
      </div>
    </motion.div>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline gap-1.5">
      <dt className="text-fg-muted">{label}</dt>
      <dd className="text-fg-subtle">{children}</dd>
    </div>
  );
}

/**
 * The one thing this screen must say that nothing else in the product does.
 *
 * A merchant arrives here holding four keys they have never seen the secrets of: onboarding issued
 * them and the portal discarded the values by design (`merchants.ts`). Nothing on the row explains
 * why a perfectly healthy key cannot be used, and the reasonable conclusion — "the keys are
 * broken" — is wrong in a way that costs an hour.
 *
 * The copy is written to stay true after the user creates their own key, which is the state this
 * notice is still showing in: it states the rule and the remedy conditionally rather than asserting
 * that no secret is held. A banner that said "these keys have no secret" to someone who had just
 * copied one would be the same failure in the opposite direction.
 */
export function LostSecretNotice() {
  return (
    <div className="mb-5 flex items-start gap-2.5 rounded-md bg-surface-inset p-3.5 ring-hairline">
      <Info aria-hidden className="mt-0.5 size-4 shrink-0 text-info" />
      <div>
        <p className="text-label font-[510] text-fg">
          Don&rsquo;t have the secret for one of these?
        </p>
        <p className="mt-0.5 text-label text-fg-subtle">
          Secrets are shown once, at creation, and the platform stores only a hash — including for
          the starter keys your account was created with, whose secrets were never displayed. Rotate
          a key to get a working secret for it, or create a new one.
        </p>
      </div>
    </div>
  );
}

/* ── Create ────────────────────────────────────────────────────────────────────────── */

export function CreateKeyButton({ csrfToken, mode }: { csrfToken: string; mode: 'test' | 'live' }) {
  const [open, setOpen] = useState(false);
  const [state, formAction] = useActionState<KeyActionState, FormData>(createKeyAction, IDLE);
  const [type, setType] = useState<'SECRET' | 'PUBLISHABLE'>('SECRET');
  const nameId = useId();

  return (
    <>
      <Button variant="primary" onClick={() => setOpen(true)}>
        <Plus />
        Create key
      </Button>

      <Dialog open={open && !state.issued} onOpenChange={setOpen}>
        <DialogContent open={open && !state.issued} className="sm:max-w-lg">
          <form action={formAction} className="flex flex-col gap-5">
            <input type="hidden" name={CSRF_FIELD} value={csrfToken} />

            <DialogHeader>
              <DialogTitle>Create an API key</DialogTitle>
              <DialogDescription>
                The secret is shown once, immediately after it is created, and never again.
              </DialogDescription>
            </DialogHeader>

            <div className="flex flex-col gap-1.5">
              <label htmlFor={nameId} className="text-label font-[510] text-fg">
                Name
              </label>
              <Input
                id={nameId}
                name="name"
                maxLength={100}
                placeholder="Production server"
                autoComplete="off"
              />
              <p className="text-label-sm text-fg-muted">
                Optional. Used to tell your keys apart — and to confirm a revocation.
              </p>
              {state.field === 'name' ? (
                <p className="text-label-sm text-danger">{state.error}</p>
              ) : null}
            </div>

            <ChoiceGroup
              legend="Type"
              name="type"
              value={type}
              onChange={(next) => setType(next as 'SECRET' | 'PUBLISHABLE')}
              options={[
                { value: 'SECRET', label: 'Secret', hint: 'Server-side only. Full access.' },
                {
                  value: 'PUBLISHABLE',
                  label: 'Publishable',
                  hint: 'Safe in a browser. Read-only.',
                },
              ]}
            />

            {/*
             * §6.3 asks for a mode selector here. Choosing the mode you are not viewing moves the
             * session to it (see `actions.ts`), so the key you just made is the one you land on
             * — rather than being minted into a list you cannot see.
             */}
            <ChoiceGroup
              legend="Mode"
              name="mode"
              defaultValue={mode}
              options={[
                { value: 'test', label: 'Test', hint: 'Simulated money.' },
                { value: 'live', label: 'Live', hint: 'Real money, real customers.' },
              ]}
            />

            <fieldset className="flex flex-col gap-2">
              <legend className="mb-1.5 text-label font-[510] text-fg">Permissions</legend>
              {SCOPES.map((scope) => (
                <label
                  key={scope.value}
                  className="flex cursor-pointer items-start gap-2.5 rounded-md p-2 ring-inset hover:bg-surface-inset has-checked:bg-surface-inset"
                >
                  <input
                    type="checkbox"
                    name="scopes"
                    value={scope.value}
                    defaultChecked={
                      type === 'SECRET' ? scope.value === '*' : scope.value === 'payments:read'
                    }
                    className="mt-0.5 size-3.5 accent-[var(--color-accent)]"
                  />
                  <span>
                    <span className="block text-label text-fg">{scope.label}</span>
                    <span className="block text-label-sm text-fg-muted">{scope.hint}</span>
                  </span>
                </label>
              ))}
            </fieldset>

            <FormAlert>{state.field === undefined ? state.error : undefined}</FormAlert>

            <DialogFooter>
              <Button type="button" variant="secondary" onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <SubmitButton idle="Create key" busy="Creating…" />
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <RevealDialog issued={state.issued} occasion="created" csrfToken={csrfToken} />
    </>
  );
}

/* ── Rotate ────────────────────────────────────────────────────────────────────────── */

function RotateKeyButton({ apiKey, csrfToken }: { apiKey: ApiKeySummary; csrfToken: string }) {
  const [open, setOpen] = useState(false);
  const [state, formAction] = useActionState<KeyActionState, FormData>(rotateKeyAction, IDLE);

  return (
    <>
      <Button variant="secondary" size="sm" onClick={() => setOpen(true)}>
        <RefreshCw />
        Rotate
      </Button>

      <Dialog open={open && !state.issued} onOpenChange={setOpen}>
        <DialogContent open={open && !state.issued}>
          <form action={formAction} className="flex flex-col gap-5">
            <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
            <input type="hidden" name="keyId" value={apiKey.id} />

            <DialogHeader>
              <DialogTitle>Rotate {apiKey.name}?</DialogTitle>
              <DialogDescription>
                A new key is issued with the same type, mode and permissions, and its secret is
                shown once.
              </DialogDescription>
            </DialogHeader>

            <ul className="flex flex-col gap-2 rounded-md bg-surface-inset p-3.5 text-label text-fg-subtle">
              <li className="flex items-start gap-2">
                <Check aria-hidden className="mt-0.5 size-3.5 shrink-0 text-success" />
                <span>
                  <span className="font-mono">{apiKey.keyPrefix}…</span> keeps working during a
                  grace window, so nothing breaks mid-deploy.
                </span>
              </li>
              <li className="flex items-start gap-2">
                <AlertTriangle aria-hidden className="mt-0.5 size-3.5 shrink-0 text-warning" />
                <span>
                  When that window ends, the old key stops. The exact deadline appears on this
                  screen as soon as the rotation completes.
                </span>
              </li>
            </ul>

            <FormAlert>{state.error}</FormAlert>

            <DialogFooter>
              <Button type="button" variant="secondary" onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <SubmitButton idle="Rotate key" busy="Rotating…" />
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <RevealDialog issued={state.issued} occasion="rotated" csrfToken={csrfToken} />
    </>
  );
}

/* ── Revoke ────────────────────────────────────────────────────────────────────────── */

function RevokeKeyButton({ apiKey, csrfToken }: { apiKey: ApiKeySummary; csrfToken: string }) {
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState('');
  const [state, formAction] = useActionState<KeyActionState, FormData>(revokeKeyAction, IDLE);
  const confirmId = useId();

  // A completed revocation has nothing to reveal, so the page reloads to show the list as it now
  // is. Same reasoning as the reveal: the server is the only thing that can state the new truth.
  useEffect(() => {
    if (state.done === 'revoked') window.location.assign('/developers/api-keys');
  }, [state.done]);

  const matches = typed.trim() === apiKey.name.trim();

  return (
    <>
      <Button
        variant="ghost"
        size="sm"
        onClick={() => setOpen(true)}
        aria-label={`Revoke ${apiKey.name}`}
      >
        <Trash2 />
        Revoke
      </Button>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent open={open}>
          <form action={formAction} className="flex flex-col gap-5">
            <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
            <input type="hidden" name="keyId" value={apiKey.id} />

            <DialogHeader>
              <DialogTitle>Revoke {apiKey.name}?</DialogTitle>
              <DialogDescription>
                This takes effect immediately and cannot be undone. Any server still using this key
                starts failing on its next request.
              </DialogDescription>
            </DialogHeader>

            <div className="flex flex-col gap-1.5">
              <label htmlFor={confirmId} className="text-label text-fg-subtle">
                Type <span className="font-[510] text-fg">{apiKey.name}</span> to confirm
              </label>
              <Input
                id={confirmId}
                name="confirmation"
                value={typed}
                onChange={(event) => setTyped(event.target.value)}
                autoComplete="off"
                autoCapitalize="off"
                spellCheck={false}
                aria-describedby={state.field === 'confirmation' ? `${confirmId}-error` : undefined}
              />
              {state.field === 'confirmation' ? (
                <p id={`${confirmId}-error`} className="text-label-sm text-danger">
                  {state.error}
                </p>
              ) : null}
            </div>

            <FormAlert>{state.field === undefined ? state.error : undefined}</FormAlert>

            <DialogFooter>
              <Button type="button" variant="secondary" onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <SubmitButton idle="Revoke key" busy="Revoking…" tone="danger" disabled={!matches} />
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}

/* ── The reveal ────────────────────────────────────────────────────────────────────── */

/**
 * The one screen in the product that shows a secret.
 *
 * Everything about it is shaped by the fact that closing it destroys the value: it cannot be
 * dismissed by clicking away or by Escape, the copy button reports success rather than assuming
 * it, and the acknowledge button says what acknowledging costs.
 */
function RevealDialog({
  issued,
  occasion,
  csrfToken,
}: {
  issued: IssuedApiKey | undefined;
  occasion: 'created' | 'rotated';
  csrfToken: string;
}) {
  const [copied, setCopied] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = setTimeout(() => setCopied(false), 2000);
    return () => clearTimeout(timer);
  }, [copied]);

  if (!issued) return null;

  async function copy(value: string) {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
    } catch {
      // Clipboard access can be denied outright. Saying nothing would look like a broken button,
      // and the value is selectable on screen either way.
      setCopied(false);
    }
  }

  return (
    <Dialog open>
      <DialogContent
        open
        className="sm:max-w-xl"
        onInteractOutside={(event) => event.preventDefault()}
        onEscapeKeyDown={(event) => event.preventDefault()}
        showClose={false}
      >
        <div className="flex flex-col gap-5">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <KeyRound aria-hidden className="size-4 text-accent" />
              Your key is {occasion === 'created' ? 'ready' : 'rotated'}
            </DialogTitle>
            <DialogDescription>
              Copy it now. This is the only time it will ever be shown — the platform stores a hash,
              not the key, so it cannot be recovered.
            </DialogDescription>
          </DialogHeader>

          <div className="flex flex-col gap-2">
            <div className="flex items-center gap-2">
              <Badge tone={issued.mode === 'live' ? 'danger' : 'test'} dot>
                {issued.mode === 'live' ? 'Live' : 'Test'}
              </Badge>
              <Badge tone="outline">{typeLabel(issued.type)}</Badge>
              <span className="truncate text-label text-fg-subtle">{issued.name}</span>
            </div>

            <div className="flex items-stretch gap-2">
              {/*
               * Read-only rather than a <p>: it gives the value a selection affordance and a
               * predictable copy target on mobile, where selecting text inside a paragraph is
               * genuinely difficult. It carries no `name`, so it is not part of any form.
               */}
              <input
                readOnly
                value={issued.apiKey}
                aria-label="Your new API key"
                data-testid="revealed-secret"
                onFocus={(event) => event.currentTarget.select()}
                className="min-w-0 flex-1 rounded-md bg-surface-inset px-3 py-2.5 font-mono text-label-sm text-fg ring-hairline outline-none focus-visible:ring-2 focus-visible:ring-accent"
              />
              <Button type="button" variant="secondary" onClick={() => void copy(issued.apiKey)}>
                <AnimatePresence mode="wait" initial={false}>
                  <motion.span
                    key={copied ? 'copied' : 'copy'}
                    initial={{ opacity: 0, y: -3 }}
                    animate={{ opacity: 1, y: 0 }}
                    exit={{ opacity: 0, y: 3 }}
                    transition={{ duration: 0.12 }}
                    className="flex items-center gap-1.5"
                  >
                    {copied ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
                    {copied ? 'Copied' : 'Copy'}
                  </motion.span>
                </AnimatePresence>
              </Button>
            </div>
            <p role="status" className="min-h-4 text-label-sm text-success">
              {copied ? 'Copied to your clipboard.' : ''}
            </p>
          </div>

          <label className="flex cursor-pointer items-start gap-2.5 rounded-md bg-surface-inset p-3.5">
            <input
              type="checkbox"
              checked={acknowledged}
              onChange={(event) => setAcknowledged(event.target.checked)}
              className="mt-0.5 size-3.5 accent-[var(--color-accent)]"
            />
            <span className="text-label text-fg-subtle">
              I have stored this key somewhere safe. I understand it cannot be shown again.
            </span>
          </label>

          {/*
           * Acknowledging leaves through the portal's own mode control.
           *
           * A key may have been minted in the mode the session is not in — §6.3 puts that choice
           * on the create form — and the list shows one mode at a time. `POST /api/session/mode`
           * is where mode has been changed since M23.2 (D184): it validates the value, reseals the
           * session, and redirects back to the page it was submitted from. Submitting it here
           * therefore lands the user on this route, in the mode their new key belongs to, as a
           * fresh document render.
           *
           * It is submitted unconditionally rather than only when the mode differs. The handler
           * is a no-op when it already matches, and one path is one thing to get right — the
           * branch would be a second, taken rarely, and rarely-taken branches are where the bugs
           * live. `actions.ts` records what happened when this ran *before* the reveal instead.
           */}
          <form action="/api/session/mode" method="post">
            <input type="hidden" name={CSRF_FIELD} value={csrfToken} />
            <input type="hidden" name="mode" value={issued.mode} />
            <DialogFooter>
              <Button type="submit" variant="primary" disabled={!acknowledged}>
                Done
              </Button>
            </DialogFooter>
          </form>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/* ── Shared bits ───────────────────────────────────────────────────────────────────── */

function SubmitButton({
  idle,
  busy,
  tone = 'primary',
  disabled = false,
}: {
  idle: string;
  busy: string;
  tone?: 'primary' | 'danger';
  disabled?: boolean;
}) {
  const { pending } = useFormStatus();
  return (
    <Button
      type="submit"
      variant={tone === 'danger' ? 'danger' : 'primary'}
      disabled={pending || disabled}
    >
      {pending ? busy : idle}
    </Button>
  );
}

function ChoiceGroup({
  legend,
  name,
  options,
  value,
  defaultValue,
  onChange,
}: {
  legend: string;
  name: string;
  options: readonly { value: string; label: string; hint: string }[];
  value?: string;
  defaultValue?: string;
  onChange?: (next: string) => void;
}) {
  return (
    <fieldset>
      <legend className="mb-1.5 text-label font-[510] text-fg">{legend}</legend>
      <div className="grid gap-2 sm:grid-cols-2">
        {options.map((option) => (
          <label
            key={option.value}
            className="flex cursor-pointer items-start gap-2.5 rounded-md bg-surface-inset p-3 ring-hairline has-checked:ring-2 has-checked:ring-accent"
          >
            <input
              type="radio"
              name={name}
              value={option.value}
              {...(value === undefined
                ? { defaultChecked: defaultValue === option.value }
                : { checked: value === option.value, onChange: () => onChange?.(option.value) })}
              className="mt-0.5 size-3.5 accent-[var(--color-accent)]"
            />
            <span>
              <span className="block text-label text-fg">{option.label}</span>
              <span className="block text-label-sm text-fg-muted">{option.hint}</span>
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}
