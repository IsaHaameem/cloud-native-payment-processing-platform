import type { Metadata } from 'next';

import { PageHeader } from '@/components/patterns/page-header';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { CodeBlock } from '@/components/ui/code-block';
import { API_VERSION } from '@/generated/contract';
import { requireMerchant } from '@/lib/session/require';

export const metadata: Metadata = { title: 'SDKs' };

/**
 * The SDKs page (frontend build).
 *
 * `frontend_Design.md §5` — SDKs is "static, from repository source". Every client checks its
 * generated-equivalent layer against the same contract fixtures this dashboard uses, so a method
 * shown here exists in the client you install. No package is published to a public registry yet
 * (`sdks/PUBLISHING.md`), so the install lines say how to get it from the repo.
 */
export const dynamic = 'force-dynamic';

const NODE = `# publish-ready, not on npm — build from sdks/node in the repo
import { PaymentFlow } from 'paymentflow';

const client = new PaymentFlow({
  apiKey: process.env.PAYMENTFLOW_API_KEY,
});

const payment = await client.payments.create(
  { amountMinor: 649000, currency: 'INR' },
  { idempotencyKey: crypto.randomUUID() },
);

await client.payments.authorize(payment.id);
await client.payments.capture(payment.id);`;

const PY = `# publish-ready, not on PyPI — pip install . in sdks/python
from paymentflow import PaymentFlow
import uuid

client = PaymentFlow()   # reads PAYMENTFLOW_API_KEY

payment = client.payments.create(
    amount_minor=649000,
    currency="INR",
    idempotency_key=str(uuid.uuid4()),
)

client.payments.authorize(payment["id"])
client.payments.capture(payment["id"])`;

const JAVA = `// publish-ready, not on Maven Central — ./gradlew publishToMavenLocal in sdks/java
// implementation("io.github.isahaameem:paymentflow:0.1.0")
import dev.paymentflow.PaymentFlow;
import dev.paymentflow.resources.Payments;

PaymentFlow client = PaymentFlow.builder()
    .apiKey(System.getenv("PAYMENTFLOW_API_KEY"))
    .build();

var payment = client.payments().create(
    Payments.params().amountMinor(649000).currency("INR"),
    dev.paymentflow.RequestOptions.builder()
        .idempotencyKey(java.util.UUID.randomUUID().toString()).build());

client.payments().authorize(payment.id());
client.payments().capture(payment.id());`;

const GO = `// publish-ready, not tagged — replace directive against a checkout of sdks/go
import (
    "context"
    paymentflow "github.com/IsaHaameem/cloud-native-payment-processing-platform/sdks/go"
)

client, _ := paymentflow.NewClient("") // reads PAYMENTFLOW_API_KEY
ctx := context.Background()

payment, _ := client.Payments.Create(ctx, paymentflow.CreatePaymentParams{
    AmountMinor: 649000,
    Currency:    "INR",
}, paymentflow.WithIdempotencyKey("charge-" + orderID))

client.Payments.Authorize(ctx, payment.ID)
client.Payments.Capture(ctx, payment.ID)`;

export default async function SdksPage() {
  await requireMerchant();
  return (
    <div>
      <PageHeader
        title="SDKs"
        description="Node.js, Python, Java and Go clients, each checked against the same OpenAPI contract this dashboard and the request validation are driven from."
        actions={
          <Badge tone="outline">
            <span className="font-mono">against {API_VERSION}</span>
          </Badge>
        }
      />

      <div className="grid gap-4 grid-cols-1 lg:grid-cols-2">
        <div>
          <h2 className="mb-2 text-label font-[510] text-fg">Node.js</h2>
          <CodeBlock samples={[{ label: 'index.ts', code: NODE }]} />
        </div>
        <div>
          <h2 className="mb-2 text-label font-[510] text-fg">Python</h2>
          <CodeBlock samples={[{ label: 'main.py', code: PY }]} />
        </div>
        <div>
          <h2 className="mb-2 text-label font-[510] text-fg">Java</h2>
          <CodeBlock samples={[{ label: 'Payment.java', code: JAVA }]} />
        </div>
        <div>
          <h2 className="mb-2 text-label font-[510] text-fg">Go</h2>
          <CodeBlock samples={[{ label: 'payment.go', code: GO }]} />
        </div>
      </div>

      <Card className="mt-4 border border-mode-test-border bg-mode-test-surface">
        <CardContent className="pt-4">
          <p className="text-label text-fg-muted">
            <span className="font-[510] text-mode-test">Publish-ready, not published.</span> All
            four packages build and pass their full test suites, but none is on a public registry —
            <span className="font-mono"> sdks/PUBLISHING.md</span> in the repository lists exactly
            what a release needs. Build from the repo as shown, or call the REST API directly
            meanwhile: every SDK method maps one-to-one to a documented endpoint.
          </p>
        </CardContent>
      </Card>

      <Card className="mt-4">
        <CardContent className="pt-4">
          <h2 className="mb-2 text-label font-[510] text-fg">What the SDKs give you</h2>
          <ul className="space-y-1.5 text-label text-fg-subtle">
            <li>— Typed models emitted from the contract; a build gate fails when they drift.</li>
            <li>
              — Retry on 429 and transient 5xx, with <code className="font-mono">Retry-After</code>{' '}
              honoured.
            </li>
            <li>
              — The error taxonomy mapped to exception classes you branch on by{' '}
              <code className="font-mono">type</code>.
            </li>
            <li>
              — Idempotency-Key generation. Supply your own when a repeat must be detectable as a
              repeat rather than a fresh attempt.
            </li>
          </ul>
        </CardContent>
      </Card>
    </div>
  );
}
