/**
 * A webhook receiver, using only Node's own http module.
 *
 * Run: PAYMENTFLOW_WEBHOOK_SECRET=whsec_… npx tsx examples/05-webhook-receiver.ts
 *
 * No framework, on purpose — the one thing that matters here is that the verification runs
 * against the **raw request body**, and every framework has its own way of getting in the way
 * of that. In Express it is `express.raw({ type: 'application/json' })` on this route, before
 * `express.json()` ever sees it. `JSON.parse` followed by `JSON.stringify` does not round-trip
 * bytes, so a re-serialized body fails a signature that was perfectly valid.
 *
 * Note there is no API key here. Verification needs the endpoint's signing secret and nothing
 * else, so a receiver never has to hold a secret key it would not otherwise need.
 */
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import {
  constructEvent,
  SIGNATURE_HEADER,
  WebhookSignatureError,
  WebhookTimestampError,
  WebhookVerificationError,
} from 'paymentflow';

const SECRET = process.env['PAYMENTFLOW_WEBHOOK_SECRET'] ?? '';

/** Collects the body as bytes. Never decoded, never parsed, until it has been verified. */
async function rawBody(request: IncomingMessage): Promise<Buffer> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) {
    chunks.push(chunk as Buffer);
  }
  return Buffer.concat(chunks);
}

const seen = new Set<string>();

const server = createServer((request: IncomingMessage, response: ServerResponse): void => {
  void (async (): Promise<void> => {
    const body = await rawBody(request);
    const signature = request.headers[SIGNATURE_HEADER.toLowerCase()];

    let event;
    try {
      event = constructEvent(body, typeof signature === 'string' ? signature : '', SECRET);
    } catch (error: unknown) {
      if (error instanceof WebhookTimestampError) {
        // A valid signature arriving late is a replayed delivery, or a clock that is wrong.
        // Worth distinguishing: one is an attack and the other is NTP.
        console.warn(`stale delivery, ${error.skewSeconds}s of skew`);
        response.writeHead(400).end('stale');
        return;
      }
      if (error instanceof WebhookSignatureError) {
        // This did not come from PaymentFlow, or did not arrive intact. Do not act on it.
        console.warn(`rejected: ${error.message}`);
        response.writeHead(400).end('bad signature');
        return;
      }
      if (error instanceof WebhookVerificationError) {
        console.error(`verified but unusable: ${error.message}`);
        response.writeHead(400).end('bad payload');
        return;
      }
      throw error;
    }

    // Deliveries repeat — after a retry that actually succeeded, after a manual replay, or
    // during a partition. `event.id` is stable across all of those.
    if (seen.has(event.id)) {
      response.writeHead(200).end('duplicate');
      return;
    }
    seen.add(event.id);

    // Answer quickly, then do the work. Anything slower than 5 seconds counts as a failed
    // attempt and enters the retry schedule.
    response.writeHead(200).end('ok');

    switch (event.type) {
      case 'payment.captured': {
        const payment = event.data.object ?? {};
        console.log(`captured ${String(payment['id'])} for ${String(payment['amountMinor'])}`);
        break;
      }
      case 'payment.failed':
        console.log(`failed ${String((event.data.object ?? {})['id'])}`);
        break;
      default:
        // New event types ship without a new API revision, so ignore what you do not know
        // rather than erroring on it.
        console.log(`ignoring ${event.type}`);
    }
  })().catch((error: unknown) => {
    console.error(error);
    if (!response.headersSent) response.writeHead(500).end();
  });
});

server.listen(4242, () => console.log('listening on :4242'));
