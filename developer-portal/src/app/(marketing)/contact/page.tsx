import type { Metadata } from 'next';

import { MarketingSection } from '@/components/marketing/marketing-section';

import { ContactForm } from './contact-form';

export const metadata: Metadata = {
  title: 'Contact',
  description:
    'You do not need to talk to anyone to start — sandbox access is self-serve. Get in touch for production terms, an architecture question, or a security disclosure.',
};

/** Reference: `Contact.dc.html`. */

const ROUTES = [
  {
    tag: 'Sales',
    title: 'Production terms',
    body: 'Volume, currencies and provider mix determine the quote. Bring your acquirer relationships — we orchestrate them rather than replace them.',
  },
  {
    tag: 'Engineering',
    title: 'Architecture questions',
    body: 'Idempotency boundaries, ledger semantics, webhook reconciliation, version pinning. Answered by whoever wrote that part.',
  },
  {
    tag: 'Security',
    title: 'Vulnerability disclosure',
    body: 'Include reproduction steps and the requestId from any affected call. We acknowledge, triage, and tell you what we found.',
  },
  {
    tag: 'Self-serve',
    title: 'You may not need us',
    body: 'Sandbox is free, unmetered and does not expire. Build the whole integration before speaking to anyone.',
  },
];

export default function ContactPage() {
  return (
    <MarketingSection
      eyebrow="Contact"
      title="Talk to the people who built it."
      lede="You do not need to talk to anyone to start — sandbox access is self-serve and unmetered. Get in touch when you want production terms, have an architecture question, or found something we should fix."
      bordered={false}
    >
      <div className="grid gap-10 grid-cols-1 lg:grid-cols-2">
        <div className="divide-y divide-border-subtle">
          {ROUTES.map((r) => (
            <div key={r.tag} className="flex flex-wrap gap-3 py-4">
              <span className="w-28 shrink-0 font-mono text-caption tracking-[0.08em] text-fg-subtle uppercase">
                {r.tag}
              </span>
              <div className="min-w-[14rem] flex-1">
                <p className="text-label text-fg">{r.title}</p>
                <p className="mt-0.5 text-label text-fg-subtle">{r.body}</p>
              </div>
            </div>
          ))}
        </div>

        <ContactForm />
      </div>
    </MarketingSection>
  );
}
