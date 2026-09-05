'use client';

import { Check } from 'lucide-react';
import * as React from 'react';

import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';

/**
 * The contact form (frontend build).
 *
 * ── Why it composes a mailto rather than posting ─────────────────────────────────────
 *
 * There is no contact endpoint in this platform, and the design's own draft surfaces that
 * plainly ("no form endpoint is reachable"). Rather than a dead button or a faked success, the
 * form is real: it validates, then hands the composed message to the visitor's mail client with
 * every field carried into the subject and body. The send genuinely happens — in their client,
 * not on a server we do not have.
 */

const TOPICS = [
  'Production terms',
  'Agentic commerce',
  'Architecture review',
  'Security disclosure',
];
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const CONTACT_ADDRESS = 'hello@paymentflow.dev';

export function ContactForm() {
  const [name, setName] = React.useState('');
  const [email, setEmail] = React.useState('');
  const [company, setCompany] = React.useState('');
  const [topic, setTopic] = React.useState(TOPICS[0] as string);
  const [message, setMessage] = React.useState('');
  const [touched, setTouched] = React.useState(false);
  const [handedOff, setHandedOff] = React.useState(false);

  const emailBad = touched && !EMAIL.test(email);

  function submit() {
    setTouched(true);
    if (!EMAIL.test(email)) return;
    const body = [
      `Name: ${name || '—'}`,
      `Email: ${email}`,
      `Company: ${company || '—'}`,
      `Topic: ${topic}`,
      '',
      message || '(no additional detail)',
    ].join('\n');
    const href = `mailto:${CONTACT_ADDRESS}?subject=${encodeURIComponent(
      `PaymentFlow — ${topic}`,
    )}&body=${encodeURIComponent(body)}`;
    window.location.href = href;
    setHandedOff(true);
  }

  if (handedOff) {
    return (
      <Card>
        <CardContent className="pt-5">
          <span className="inline-flex items-center gap-1.5 rounded-full bg-success-surface px-2 py-0.5 text-label-sm font-[510] text-success">
            <Check className="size-3" aria-hidden />
            Handed to your mail client
          </span>
          <h2 className="mt-3 text-title-2 font-[510] text-fg">Nearly there</h2>
          <p className="mt-2 text-label text-fg-subtle">
            Your email client should have opened with the message pre-filled to{' '}
            <span className="font-mono text-fg-muted">{CONTACT_ADDRESS}</span>. Send it and a person
            — not an autoresponder — replies within one business day. Sandbox access is open in the
            meantime.
          </p>
          <div className="mt-4 flex flex-wrap gap-2">
            <Button variant="primary" size="md" asChild>
              <a href="/signup">Start in test mode</a>
            </Button>
            <Button variant="secondary" size="md" onClick={() => setHandedOff(false)}>
              Edit the message
            </Button>
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardContent className="pt-5">
        <h2 className="text-title-2 font-[510] text-fg">Contact sales</h2>
        <p className="mt-1 text-label text-fg-subtle">
          Enough to route you to the right person. We reply within one business day.
        </p>

        <div className="mt-5 space-y-3.5">
          <Field label="Name">
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              autoComplete="name"
              placeholder="Your name"
            />
          </Field>
          <Field label="Work email" error={emailBad ? 'Enter a valid email address.' : undefined}>
            <Input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="email"
              placeholder="you@company.com"
              aria-invalid={emailBad}
              className={emailBad ? 'ring-1 ring-danger' : undefined}
            />
          </Field>
          <Field label="Company">
            <Input
              value={company}
              onChange={(e) => setCompany(e.target.value)}
              autoComplete="organization"
              placeholder="Company name"
            />
          </Field>

          <fieldset className="space-y-2">
            <legend className="text-label text-fg-muted">What do you need?</legend>
            <div className="flex flex-wrap gap-2">
              {TOPICS.map((t) => (
                <button
                  key={t}
                  type="button"
                  onClick={() => setTopic(t)}
                  aria-pressed={topic === t}
                  className={cn(
                    'h-8 rounded-full px-3 text-label-sm font-[510] ring-1 ring-inset transition-colors',
                    topic === t
                      ? 'bg-surface-active text-fg ring-border-strong'
                      : 'text-fg-subtle ring-border hover:text-fg',
                  )}
                >
                  {t}
                </button>
              ))}
            </div>
          </fieldset>

          <Field label="Anything else">
            <textarea
              value={message}
              onChange={(e) => setMessage(e.target.value)}
              rows={4}
              placeholder="Volume, currencies, providers you already use, timeline"
              className="w-full resize-y rounded-md bg-surface-inset px-2.5 py-2 text-body text-fg ring-hairline placeholder:text-fg-subtle hover:bg-surface-hover"
            />
          </Field>

          <Button variant="primary" size="lg" className="w-full" onClick={submit}>
            Compose email
          </Button>
          <p className="text-label-sm text-fg-subtle">
            This opens your mail client with the details filled in. We use your address to reply and
            nothing else.
          </p>
        </div>
      </CardContent>
    </Card>
  );
}

function Field({
  label,
  error,
  children,
}: {
  label: string;
  error?: string | undefined;
  children: React.ReactNode;
}) {
  return (
    <label className="block space-y-1.5">
      <span className="text-label text-fg-muted">{label}</span>
      {children}
      {error ? <span className="block text-label-sm text-danger">{error}</span> : null}
    </label>
  );
}
