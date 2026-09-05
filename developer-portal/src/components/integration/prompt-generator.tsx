'use client';

import { Check, Sparkles, Terminal } from 'lucide-react';
import * as React from 'react';

import { OptionGrid } from '@/components/integration/option-grid';
import { CopyButton } from '@/components/ui/copy-button';
import { generateIntegrationPrompt, promptSummary } from '@/lib/integration/prompt';
import { APP_TYPES, FEATURES, STACKS } from '@/lib/integration/stacks';
import type { Mode } from '@/lib/session/session';

/**
 * "Let your coding agent integrate PaymentFlow."
 *
 * Three picks — what you're building, what you're using, what to integrate — then a prompt the
 * user copies into Claude Code / Codex / Cursor. The prompt is built by
 * `generateIntegrationPrompt` entirely from the frozen contract; it carries no secret and names
 * only real endpoints. Everything here is client state — no API call, no backend dependency.
 */
export function PromptGenerator({ mode }: { mode: Mode }) {
  const [appTypeId, setAppTypeId] = React.useState(APP_TYPES[0]!.id);
  const [stackId, setStackId] = React.useState(STACKS[0]!.id);
  const [features, setFeatures] = React.useState<string[]>(
    FEATURES.filter((f) => f.defaultOn).map((f) => f.id),
  );

  // If the user picks the agent app type, default agentic commerce on.
  React.useEffect(() => {
    if (appTypeId === 'agent') {
      setFeatures((f) => (f.includes('agentic') ? f : [...f, 'agentic']));
    }
  }, [appTypeId]);

  // Plain string concatenation — cheap enough to run on every render, so no memo to keep in
  // sync with a dependency array.
  const opts = {
    appTypeId,
    stackId,
    features: features as ('payments' | 'refunds' | 'webhooks' | 'agentic')[],
    mode,
  };
  const prompt = generateIntegrationPrompt(opts);

  return (
    <div className="space-y-8">
      <Step n={1} title="What are you building?">
        <OptionGrid
          ariaLabel="Application type"
          columns={3}
          options={APP_TYPES.map((a) => ({ id: a.id, label: a.label }))}
          value={[appTypeId]}
          onChange={([id]) => id && setAppTypeId(id)}
        />
      </Step>

      <Step n={2} title="What are you using?">
        <OptionGrid
          ariaLabel="Stack"
          columns={3}
          options={STACKS.map((s) => ({ id: s.id, label: s.label }))}
          value={[stackId]}
          onChange={([id]) => id && setStackId(id)}
        />
      </Step>

      <Step n={3} title="What do you want to integrate?">
        <OptionGrid
          ariaLabel="Features"
          multi
          columns={2}
          options={FEATURES.map((f) => ({ id: f.id, label: f.label, hint: f.hint }))}
          value={features}
          onChange={(next) => setFeatures(next.length ? next : ['payments'])}
        />
      </Step>

      {/* ── The prompt ─────────────────────────────────────────────────────────────── */}
      <div className="rounded-xl bg-surface ring-hairline">
        <div className="flex flex-wrap items-center gap-2.5 border-b border-border-subtle p-4">
          <span className="flex size-7 items-center justify-center rounded-md bg-accent-subtle text-accent-text">
            <Sparkles aria-hidden className="size-4" />
          </span>
          <div className="min-w-0">
            <p className="text-label font-[510] text-fg">Your integration prompt is ready</p>
            <p className="truncate text-label-sm text-fg-subtle">{promptSummary(opts)}</p>
          </div>
          <CopyButton
            value={prompt}
            label="Copy prompt"
            variant="secondary"
            size="md"
            className="ml-auto"
          />
        </div>

        <pre className="max-h-[420px] overflow-auto p-4 font-mono text-[0.75rem] leading-[1.65] text-fg-muted whitespace-pre-wrap">
          <code>{prompt}</code>
        </pre>

        <div className="flex flex-col gap-3 border-t border-border-subtle p-4 sm:flex-row sm:items-center">
          <p className="text-label-sm text-fg-subtle">
            Paste it into your coding agent. It will inspect your project and implement the
            integration against PaymentFlow’s API — you don’t need to read the whole API first.
          </p>
          <div className="flex flex-wrap gap-1.5 sm:ml-auto sm:shrink-0">
            {['Claude Code', 'Codex', 'Cursor', 'Gemini'].map((agent) => (
              <span
                key={agent}
                className="inline-flex items-center gap-1.5 rounded-md bg-surface-inset px-2.5 py-1 text-label-sm text-fg-subtle ring-hairline"
              >
                <Terminal aria-hidden className="size-3" />
                {agent}
              </span>
            ))}
          </div>
        </div>
      </div>

      <ul className="grid gap-2 text-label-sm text-fg-subtle sm:grid-cols-2">
        {[
          'Names only real PaymentFlow endpoints — the agent is told not to invent any.',
          'Contains no secret. The agent reads your key from an environment variable.',
          `Targets ${mode} mode. No real money moves${mode === 'live' ? ' (the platform still uses a simulated acquirer)' : ''}.`,
          'Tells the agent to run your existing tests and list what it changed.',
        ].map((line) => (
          <li key={line} className="flex items-start gap-2">
            <Check aria-hidden className="mt-0.5 size-3.5 shrink-0 text-success" />
            {line}
          </li>
        ))}
      </ul>
    </div>
  );
}

function Step({ n, title, children }: { n: number; title: string; children: React.ReactNode }) {
  return (
    <section>
      <div className="mb-3 flex items-center gap-2.5">
        <span className="flex size-6 items-center justify-center rounded-full bg-surface-active text-caption font-[510] text-fg-subtle">
          {n}
        </span>
        <h2 className="text-label font-[510] text-fg">{title}</h2>
      </div>
      {children}
    </section>
  );
}
