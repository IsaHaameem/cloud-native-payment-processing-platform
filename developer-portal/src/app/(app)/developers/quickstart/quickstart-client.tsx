'use client';

import { ChevronDown, ExternalLink } from 'lucide-react';
import Link from 'next/link';
import * as React from 'react';

import { EnvVarField } from '@/components/integration/env-var-field';
import { QuickstartStep } from '@/components/integration/quickstart-step';
import { Tabs } from '@/components/ui/tabs';
import { API_VERSION } from '@/generated/contract';
import type { Mode } from '@/lib/session/session';

type Lang = 'node' | 'python' | 'java' | 'go' | 'curl';

const LANGS: { id: Lang; label: string }[] = [
  { id: 'node', label: 'Node.js' },
  { id: 'python', label: 'Python' },
  { id: 'java', label: 'Java' },
  { id: 'go', label: 'Go' },
  { id: 'curl', label: 'cURL' },
];

/**
 * "Get your first PaymentFlow payment running" — one language chosen at the top, every step's
 * code follows it. Every command is real: the SDK method names come from the SDKs in the repo
 * (publish-ready, not yet published — the install step says so), the raw calls from the frozen
 * contract, `pm_card_visa` from `GET /v1/test/cards`.
 */
export function QuickstartClient({ mode, baseUrl }: { mode: Mode; baseUrl: string }) {
  const [lang, setLang] = React.useState<Lang>('node');
  const [advanced, setAdvanced] = React.useState(false);
  const keyPrefix = `sk_${mode}_`;

  // Every SDK is publish-ready but not yet on a public registry (see the SDKs page and
  // sdks/PUBLISHING.md in the repo), so the install lines say how to get the package *today*
  // rather than printing a command that would not resolve.
  const install: Record<Lang, string | null> = {
    node: '# not yet on npm — build from sdks/node in the repo:\ngit clone <repo> && cd sdks/node && npm ci && npm run build\n# then: npm install /path/to/sdks/node   (package name: paymentflow)',
    python:
      '# not yet on PyPI — build from sdks/python in the repo:\ngit clone <repo> && cd sdks/python && pip install .\n# (package name: paymentflow)',
    java: '# not yet on Maven Central — stage it locally from sdks/java:\ngit clone <repo> && cd sdks/java && ./gradlew publishToMavenLocal\n// then, with mavenLocal() in your repositories:\n// implementation("io.github.isahaameem:paymentflow:0.1.0")',
    go: '# not yet tagged — use a replace directive against a checkout:\n# go.mod:  replace github.com/IsaHaameem/cloud-native-payment-processing-platform/sdks/go => ../path/to/sdks/go\ngo mod tidy',
    curl: null,
  };

  const createCode: Record<Lang, string> = {
    node: `import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({
  apiKey: process.env.PAYMENTFLOW_API_KEY,   // ${keyPrefix}…  — server-side only
  baseUrl: '${baseUrl}',
});

// Amounts are integers in the currency's minor unit. 1000 = 10.00.
const payment = await client.payments.create({
  amountMinor: 1000,
  currency: 'USD',
  description: 'Order A-1234',
  paymentMethodToken: 'pm_card_visa',        // a test card that approves
});

console.log(payment.id, payment.status);    // -> "pay_…", "created"`,
    python: `from paymentflow import PaymentFlow

client = PaymentFlow(base_url="${baseUrl}")   # reads PAYMENTFLOW_API_KEY (${keyPrefix}…)

# Amounts are integers in the currency's minor unit. 1000 = 10.00.
payment = client.payments.create(
    amount_minor=1000,
    currency="USD",
    description="Order A-1234",
    payment_method_token="pm_card_visa",      # a test card that approves
)

print(payment["id"], payment["status"])       # -> "pay_…", "created"`,
    java: `import dev.paymentflow.PaymentFlow;
import dev.paymentflow.model.PaymentResponse;
import dev.paymentflow.resources.Payments;

PaymentFlow client = PaymentFlow.builder()
    .apiKey(System.getenv("PAYMENTFLOW_API_KEY"))   // ${keyPrefix}…  — server-side only
    .baseUrl("${baseUrl}")
    .build();

// Amounts are integers in the currency's minor unit. 1000 = 10.00.
PaymentResponse payment = client.payments().create(
    Payments.params()
        .amountMinor(1000)
        .currency("USD")
        .description("Order A-1234")
        .paymentMethodToken("pm_card_visa"));   // a test card that approves

System.out.println(payment.id() + " " + payment.status());  // -> "pay_…", "created"`,
    go: `import (
    "context"
    paymentflow "github.com/IsaHaameem/cloud-native-payment-processing-platform/sdks/go"
)

client, _ := paymentflow.NewClient("")   // reads PAYMENTFLOW_API_KEY (${keyPrefix}…)
ctx := context.Background()

// Amounts are integers in the currency's minor unit. 1000 = 10.00.
payment, err := client.Payments.Create(ctx, paymentflow.CreatePaymentParams{
    AmountMinor:        1000,
    Currency:           "USD",
    Description:        "Order A-1234",
    PaymentMethodToken: "pm_card_visa",   // a test card that approves
})

fmt.Println(payment.ID, payment.Status)  // -> "pay_…", "created"`,
    curl: `curl -sS ${baseUrl}/v1/payments \\
  -H "Authorization: Bearer $PAYMENTFLOW_API_KEY" \\
  -H "PaymentFlow-Version: ${API_VERSION}" \\
  -H "Idempotency-Key: $(uuidgen)" \\
  -H "Content-Type: application/json" \\
  -d '{"amountMinor": 1000, "currency": "USD",
       "description": "Order A-1234",
       "paymentMethodToken": "pm_card_visa"}'`,
  };

  const captureCode: Record<Lang, string> = {
    node: `const authorized = await client.payments.authorize(payment.id);
if (authorized.status === 'failed') {
  // The acquirer's own reason — show it, let the customer retry.
  throw new Error(authorized.failureReason);
}
const captured = await client.payments.capture(payment.id);
console.log(captured.status, captured.capturedAmountMinor); // -> "captured", 1000`,
    python: `authorized = client.payments.authorize(payment["id"])
if authorized["status"] == "failed":
    # The acquirer's own reason — show it, let the customer retry.
    raise RuntimeError(authorized["failureReason"])
captured = client.payments.capture(payment["id"])
print(captured["status"], captured["capturedAmountMinor"])  # -> "captured", 1000`,
    java: `PaymentResponse authorized = client.payments().authorize(payment.id());
if ("failed".equals(authorized.status())) {
    // The acquirer's own reason — show it, let the customer retry.
    throw new IllegalStateException(authorized.failureReason());
}
PaymentResponse captured = client.payments().capture(payment.id());
System.out.println(captured.status() + " " + captured.capturedAmountMinor()); // -> "captured", 1000`,
    go: `authorized, _ := client.Payments.Authorize(ctx, payment.ID)
if authorized.Status == "failed" {
    // The acquirer's own reason — show it, let the customer retry.
    log.Fatal(authorized.FailureReason)
}
captured, _ := client.Payments.Capture(ctx, payment.ID)
fmt.Println(captured.Status, captured.CapturedAmountMinor) // -> "captured", 1000`,
    curl: `# PAYMENT_ID is the "id" from the previous response.
curl -sS ${baseUrl}/v1/payments/$PAYMENT_ID/authorize -X POST \\
  -H "Authorization: Bearer $PAYMENTFLOW_API_KEY" \\
  -H "PaymentFlow-Version: ${API_VERSION}" \\
  -H "Idempotency-Key: $(uuidgen)"

curl -sS ${baseUrl}/v1/payments/$PAYMENT_ID/capture -X POST \\
  -H "Authorization: Bearer $PAYMENTFLOW_API_KEY" \\
  -H "PaymentFlow-Version: ${API_VERSION}" \\
  -H "Idempotency-Key: $(uuidgen)"`,
  };

  const verifyCode: Record<Lang, string> = {
    node: `const check = await client.payments.retrieve(payment.id);
console.log(check.status); // -> "captured"`,
    python: `check = client.payments.retrieve(payment["id"])
print(check["status"])  # -> "captured"`,
    java: `PaymentResponse check = client.payments().retrieve(payment.id());
System.out.println(check.status()); // -> "captured"`,
    go: `check, _ := client.Payments.Retrieve(ctx, payment.ID)
fmt.Println(check.Status) // -> "captured"`,
    curl: `curl -sS ${baseUrl}/v1/payments/$PAYMENT_ID \\
  -H "Authorization: Bearer $PAYMENTFLOW_API_KEY" \\
  -H "PaymentFlow-Version: ${API_VERSION}"`,
  };

  return (
    <div>
      <div className="mb-6 overflow-x-auto">
        <Tabs
          aria-label="Language"
          items={LANGS}
          value={lang}
          onValueChange={(id) => setLang(id as Lang)}
        />
      </div>

      <ol className="space-y-8">
        <QuickstartStep
          n={1}
          title="Create a test key"
          expect={
            <>
              you see a key starting <span className="font-mono">{keyPrefix}</span> exactly once.
              Copy it now — it is never shown again.
            </>
          }
        >
          On the{' '}
          <Link href="/developers/api-keys" className="text-accent-text hover:underline">
            API keys
          </Link>{' '}
          page, create a <span className="font-[510] text-fg">Secret</span> key in{' '}
          <span className="font-[510] text-fg">{mode === 'test' ? 'Test' : 'Live'} mode</span>.
        </QuickstartStep>

        <QuickstartStep
          n={2}
          title="Add it to your server environment"
          expect={
            <>
              your server can read <span className="font-mono">PAYMENTFLOW_API_KEY</span>. Restart
              it after editing <span className="font-mono">.env</span>.
            </>
          }
        >
          <div className="mt-3">
            <EnvVarField name="PAYMENTFLOW_API_KEY" value={`${keyPrefix}your_key_here`} />
          </div>
        </QuickstartStep>

        {install[lang] ? (
          <QuickstartStep
            n={3}
            title="Get the SDK"
            samples={[{ label: LANGS.find((l) => l.id === lang)!.label, code: install[lang]! }]}
            expect={
              <>
                the <span className="font-mono">paymentflow</span> client resolves and imports. It
                has {lang === 'python' ? 'one runtime dependency' : 'zero runtime dependencies'}.
              </>
            }
          >
            <span className="font-[510] text-mode-test">Publish-ready, not yet published.</span> The{' '}
            <Link href="/developers/sdks" className="text-accent-text hover:underline">
              official SDK
            </Link>{' '}
            for this stack lives in the PaymentFlow repository and passes its full test suite, but
            is not on{' '}
            {lang === 'node'
              ? 'npm'
              : lang === 'python'
                ? 'PyPI'
                : lang === 'java'
                  ? 'Maven Central'
                  : 'the Go module proxy'}{' '}
            yet. Build it from the repo as shown, or skip it and call the REST endpoints directly
            with the three headers in each step below — every SDK method maps one-to-one to a
            documented endpoint.
          </QuickstartStep>
        ) : (
          <QuickstartStep n={3} title="No SDK — call the API directly">
            For {lang === 'curl' ? 'cURL' : 'this stack'} you call the REST endpoints with the three
            headers shown in each step. No install needed.
          </QuickstartStep>
        )}

        <QuickstartStep
          n={4}
          title="Create a payment"
          samples={[
            {
              label: LANGS.find((l) => l.id === lang)!.label,
              code: createCode[lang],
              ...(lang === 'node'
                ? { filename: 'payment.ts' }
                : lang === 'python'
                  ? { filename: 'payment.py' }
                  : lang === 'java'
                    ? { filename: 'Payment.java' }
                    : lang === 'go'
                      ? { filename: 'payment.go' }
                      : {}),
            },
          ]}
          expect={
            <>
              a payment id back, with <span className="font-mono">{'status: "created"'}</span>.
              Nothing is charged yet.
            </>
          }
        >
          One idempotent call. The <span className="font-mono">Idempotency-Key</span> (the SDK adds
          one) means a retry replays instead of charging twice.
        </QuickstartStep>

        <QuickstartStep
          n={5}
          title="Authorize, then capture"
          samples={[{ label: LANGS.find((l) => l.id === lang)!.label, code: captureCode[lang] }]}
          expect={
            <>
              <span className="font-mono">{'status: "authorized"'}</span> then{' '}
              <span className="font-mono">{'"captured"'}</span>. A declined card returns{' '}
              <span className="font-mono">{'"failed"'}</span> with a{' '}
              <span className="font-mono">failureReason</span> — surface it and let the customer
              retry.
            </>
          }
        >
          Authorize holds the amount; capture takes it. Skip capture and call{' '}
          <span className="font-mono">void</span> to release the hold instead.
        </QuickstartStep>

        <QuickstartStep
          n={6}
          title="Verify the result"
          samples={[{ label: LANGS.find((l) => l.id === lang)!.label, code: verifyCode[lang] }]}
          expect={
            <>
              <span className="font-mono">{'"captured"'}</span> — and the payment appears in{' '}
              <Link href="/payments" className="text-accent-text hover:underline">
                Payments
              </Link>{' '}
              in this dashboard, with its full lifecycle.
            </>
          }
        >
          Read the payment back any time. In production you would also receive a{' '}
          <span className="font-mono">payment.captured</span> webhook.
        </QuickstartStep>
      </ol>

      {/* ── advanced ─────────────────────────────────────────────────────────────── */}
      <div className="mt-10 border-t border-border-subtle pt-6">
        <button
          type="button"
          onClick={() => setAdvanced((v) => !v)}
          aria-expanded={advanced}
          className="flex items-center gap-1.5 text-label font-[510] text-fg-subtle hover:text-fg"
        >
          <ChevronDown
            className={`size-4 transition-transform ${advanced ? 'rotate-180' : ''}`}
            aria-hidden
          />
          Show advanced configuration
        </button>
        {advanced ? (
          <ul className="mt-4 space-y-2.5 text-label text-fg-subtle">
            <li>
              <span className="font-[510] text-fg">Client options</span> —{' '}
              <span className="font-mono">baseUrl</span>, <span className="font-mono">timeout</span>
              , <span className="font-mono">maxRetries</span>,{' '}
              <span className="font-mono">apiVersion</span> (sent as{' '}
              <span className="font-mono">PaymentFlow-Version</span>, pinned to{' '}
              <span className="font-mono">{API_VERSION}</span>).
            </li>
            <li>
              <span className="font-[510] text-fg">Partial capture &amp; multi-capture</span> — pass{' '}
              <span className="font-mono">amountMinor</span> to capture; capture again for the rest.
            </li>
            <li>
              <span className="font-[510] text-fg">Refunds</span> —{' '}
              <span className="font-mono">payments.refund(id, {'{ amountMinor?, reason? }'})</span>;
              omit the amount to refund everything remaining.
            </li>
            <li>
              <span className="font-[510] text-fg">Webhooks</span> — register an endpoint, verify
              each delivery&rsquo;s signature, reconcile against{' '}
              <span className="font-mono">GET /v1/events</span>. See{' '}
              <Link href="/developers/webhooks" className="text-accent-text hover:underline">
                Webhooks
              </Link>
              .
            </li>
            <li>
              <span className="font-[510] text-fg">Full reference</span> —{' '}
              <Link
                href="/docs"
                className="inline-flex items-center gap-1 text-accent-text hover:underline"
              >
                API documentation <ExternalLink className="size-3" />
              </Link>
              .
            </li>
          </ul>
        ) : null}
      </div>
    </div>
  );
}
