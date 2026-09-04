import { API_VERSION, DEFAULT_BASE_URL } from '@/generated/contract';

import { findAppType, findStack, type IntegrationFeature } from './stacks';

/**
 * Builds the prompt a merchant pastes into a coding agent (Claude Code, Codex, Cursor, …).
 *
 * Every fact in the output is real and comes from the frozen PaymentFlow contract
 * (`docs/openapi.yaml` → `@/generated/contract`): the base URL, the dated `PaymentFlow-Version`,
 * the `POST /v1/payments` → `authorize` → `capture` sequence, the required `Idempotency-Key`, the
 * `Authorization: Bearer sk_test_…` scheme, and the test-card catalogue at `GET /v1/test/cards`.
 * Nothing here is illustrative and no endpoint is invented — the instructions to the agent say so
 * explicitly, so a hallucinated endpoint is a rule violation the agent has been told to avoid.
 *
 * The prompt carries **no secret**. It tells the agent to read the key from the environment; the
 * portal never puts a key value into this string.
 */
export interface PromptOptions {
  readonly appTypeId: string;
  readonly stackId: string;
  readonly features: readonly IntegrationFeature['id'][];
  readonly mode: 'test' | 'live';
}

export function generateIntegrationPrompt(opts: PromptOptions): string {
  const app = findAppType(opts.appTypeId);
  const stack = findStack(opts.stackId);
  const has = (f: IntegrationFeature['id']) => opts.features.includes(f);
  const keyPrefix = opts.mode === 'live' ? 'sk_live_' : 'sk_test_';

  const lines: string[] = [];

  lines.push('# Integrate PaymentFlow into this project');
  lines.push('');
  lines.push(
    `I want you to integrate **PaymentFlow** — a payment orchestration API — into this codebase. ` +
      `The project is ${app.promptDescription}, built with ${stack.promptName}.`,
  );
  lines.push('');
  lines.push('## Ground rules');
  lines.push('');
  lines.push(
    '1. First inspect the project. Identify the framework, where server-side code lives, how it reads configuration, and how it runs its tests.',
  );
  lines.push(
    '2. Use ONLY the PaymentFlow endpoints listed below. Do not invent, guess, or "assume" any endpoint, field, or header. If something you need is not listed, stop and tell me.',
  );
  lines.push(
    '3. The PaymentFlow secret key is server-side only. It must never reach the browser, a client bundle, the DOM, `localStorage`, a URL, a log line, or version control.',
  );
  lines.push(
    '4. Read the key from an environment variable (`PAYMENTFLOW_API_KEY`). Add it to `.env.example` as an empty placeholder; never commit a real value.',
  );
  lines.push(
    `5. Use a **${opts.mode}-mode** key (\`${keyPrefix}…\`) for now. ${opts.mode === 'test' ? 'No real money moves in test mode.' : 'Only switch to live mode when I explicitly ask — and note that the current PaymentFlow platform still settles against a simulated acquirer, so no real funds move in either mode.'}`,
  );
  lines.push(
    '6. Every mutating call takes an `Idempotency-Key` header (a UUID per logical attempt). Reuse the same key when retrying the same attempt so a double-submit or a network retry replays instead of charging twice.',
  );
  lines.push(
    '7. When you are done: run the project’s existing test suite, then summarise exactly which files you changed and why. Do not commit anything.',
  );
  lines.push('');

  lines.push('## PaymentFlow API facts');
  lines.push('');
  lines.push('```');
  lines.push(`Base URL:              ${DEFAULT_BASE_URL}`);
  lines.push(`Version header:        PaymentFlow-Version: ${API_VERSION}`);
  lines.push(`Auth header:           Authorization: Bearer ${keyPrefix}<your key>`);
  lines.push(
    'Idempotency header:    Idempotency-Key: <uuid>   (required on every POST that changes state)',
  );
  lines.push(
    'Amounts:               integer minor units (e.g. 1000 = 10.00). No floating-point amounts anywhere.',
  );
  lines.push('```');
  lines.push('');
  if (stack.install) {
    if (stack.published) {
      lines.push(
        `Official SDK (recommended): install with \`${stack.install}\`. If the SDK does not fit the project, call the REST endpoints directly with the headers above.`,
      );
    } else {
      // Every SDK is publish-ready but not yet on a public registry (sdks/PUBLISHING.md), so
      // the agent must not be told to run an install command that would not resolve.
      lines.push(
        `An official ${stack.promptName} SDK exists in the PaymentFlow repository at \`${stack.repoDir}\` ` +
          `(coordinates \`${stack.install}\`). It is **publish-ready but not yet published**, so do NOT ` +
          `add it as a normal dependency. Prefer calling the REST endpoints directly with the headers ` +
          `above; only vendor the SDK from that directory if the user explicitly asks. Either way, the ` +
          `behaviour to match is: one \`Idempotency-Key\` per logical attempt reused across retries, ` +
          `\`PaymentFlow-Version: ${API_VERSION}\`, and retry only GET/DELETE or a request carrying an ` +
          `idempotency key.`,
      );
    }
    lines.push('');
  }

  if (has('payments')) {
    lines.push('### Payments — create, authorize, capture');
    lines.push('');
    lines.push('```');
    lines.push('POST /v1/payments');
    lines.push(
      '  body: { "amountMinor": <int>, "currency": "<ISO-4217>", "description": "<optional>",',
    );
    lines.push(
      '          "paymentMethodToken": "<test card token>", "metadata": { ... optional ... } }',
    );
    lines.push('  -> 201 { "id", "status": "created", "amountMinor", "currency", ... }');
    lines.push('');
    lines.push(
      'POST /v1/payments/{id}/authorize   -> { "status": "authorized" | "requires_action" | "failed", "failureReason"? }',
    );
    lines.push(
      'POST /v1/payments/{id}/capture     -> { "status": "captured", "capturedAmountMinor" }',
    );
    lines.push(
      'GET  /v1/payments/{id}             -> the payment with its current status and amounts',
    );
    lines.push('GET  /v1/payments?limit=&cursor=   -> a cursor-paginated list');
    lines.push('```');
    lines.push('');
    lines.push('Handle these outcomes explicitly:');
    lines.push(
      '- **Success**: `authorize` → `authorized`, then `capture` → `captured`. Show the payment id.',
    );
    lines.push(
      '- **Decline**: `authorize` returns `failed` with a `failureReason` (the acquirer’s own code). Surface that reason verbatim; do not invent one. Let the customer retry with a different instrument.',
    );
    lines.push(
      '- **Requires action**: `authorize` returns `requires_action`. There is no hosted step-up flow in this platform — treat it as "not captured", show it, and stop.',
    );
    lines.push(
      '- **Network / 5xx**: nothing was confirmed. Retrying with the SAME `Idempotency-Key` is safe.',
    );
    lines.push(
      '- **Duplicate submit**: guard the UI (disable the button while in flight) AND reuse the idempotency key. The platform is authoritative.',
    );
    lines.push('');
    lines.push(
      'Test instruments: `GET /v1/test/cards` returns the catalogue. Common tokens: `pm_card_visa` (approves), `pm_card_chargeDeclined` (declines), `pm_card_authRequired` (requires action), `pm_card_captureFails` (authorizes then fails at capture).',
    );
    lines.push('');
  }

  if (has('refunds')) {
    lines.push('### Refunds');
    lines.push('');
    lines.push('```');
    lines.push('POST /v1/payments/{id}/refund');
    lines.push(
      '  body: { "amountMinor": <int, optional> , "reason": "<optional>" }   // omit amountMinor to refund everything remaining',
    );
    lines.push('  -> the payment, now "partially_refunded" or "refunded"');
    lines.push('GET  /v1/refunds        GET /v1/refunds/{id}');
    lines.push('```');
    lines.push(
      'A refund also needs an `Idempotency-Key`. Never exceed `capturedAmountMinor - refundedAmountMinor`.',
    );
    lines.push('');
  }

  if (has('webhooks')) {
    lines.push('### Webhooks (optional — replaces polling)');
    lines.push('');
    lines.push('```');
    lines.push(
      'POST   /v1/webhook_endpoints        body: { "url": "https://…", "enabledEvents": ["payment.captured", ...] }',
    );
    lines.push(
      '                                    -> the endpoint + its signing secret (shown ONCE)',
    );
    lines.push(
      'GET    /v1/webhook_endpoints        GET /v1/webhook_deliveries        POST /v1/webhook_deliveries/{id}/replay',
    );
    lines.push('GET    /v1/events                   the feed to reconcile deliveries against');
    lines.push('```');
    lines.push(
      'Verify every delivery’s signature with the endpoint’s signing secret before trusting the body. Store the signing secret server-side; it is not recoverable after creation (rotate to get a new one).',
    );
    lines.push('');
  }

  if (has('agentic')) {
    lines.push('### Agentic commerce (optional)');
    lines.push('');
    lines.push(
      'PaymentFlow’s agentic layer is a **separate service** (`agentic-commerce-service`) that sits above the payment API. An AI agent proposes tool calls (`search_products`, `create_checkout`, `complete_checkout`, `request_refund`, …); a deterministic policy engine and a human-approval step decide whether each money action runs; the payment then goes through the same `/v1` API above.',
    );
    lines.push('');
    lines.push(
      '- The agent authenticates as ONE merchant API key held server-side. The browser never holds it.',
    );
    lines.push(
      '- Reach the agentic API server-side only, over the signed internal context the service verifies (see `mock-project/knitt/server/agentic.js` in this repository for a working reference proxy).',
    );
    lines.push(
      '- Do NOT bypass the policy engine or the approval step. Do NOT give the agent a generic HTTP/shell/SQL tool.',
    );
    lines.push(
      '- Refunds above the configured threshold return `stopReason: "APPROVAL_REQUIRED"` with an `approvalId`; a human calls the approve endpoint, and only then does the refund execute.',
    );
    lines.push('');
  }

  lines.push('## Definition of done');
  lines.push('');
  const done = [
    'The integration reads `PAYMENTFLOW_API_KEY` from the environment; the value is nowhere in the source tree.',
    has('payments')
      ? 'A payment can be created, authorized and captured end to end, and the payment id is shown.'
      : null,
    has('payments')
      ? 'Decline, requires-action, network failure and duplicate-submit are each handled distinctly.'
      : null,
    has('refunds') ? 'A captured payment can be refunded in full and in part.' : null,
    has('webhooks')
      ? 'A webhook endpoint is registered and its deliveries are signature-verified.'
      : null,
    has('agentic')
      ? 'The agent path runs through the policy engine and the approval step — never around them.'
      : null,
    'The project’s existing tests pass.',
    'You have listed every file you changed, and committed nothing.',
  ].filter(Boolean);
  done.forEach((d, i) => lines.push(`${i + 1}. ${d}`));
  lines.push('');
  lines.push(
    'If any required capability is missing from the facts above, tell me instead of implementing a workaround against an endpoint that does not exist.',
  );

  return lines.join('\n');
}

/** A one-line summary for the "prompt is ready" state. */
export function promptSummary(opts: PromptOptions): string {
  const app = findAppType(opts.appTypeId);
  const stack = findStack(opts.stackId);
  const feats = opts.features
    .map(
      (f) =>
        ({
          payments: 'Payments',
          refunds: 'Refunds',
          webhooks: 'Webhooks',
          agentic: 'Agentic commerce',
        })[f],
    )
    .join(' · ');
  return `${app.label} · ${stack.label} · ${feats || 'Payments'} · ${opts.mode} mode`;
}
