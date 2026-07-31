/**
 * The `PaymentFlow` client (M22.2).
 *
 * One object holding one resolved configuration and one transport, with the eleven resource
 * namespaces hanging off it. Constructed once per API key and shared: there is no connection
 * pool to warm and no state to keep, so a client is cheap, but building one per request would
 * re-read the environment and re-validate on every call for no benefit.
 *
 * The namespaces are created eagerly in the constructor rather than lazily behind getters.
 * They are eleven object allocations, they are what makes the client's shape discoverable in
 * an editor, and a getter that constructs on first access is a getter that can throw from a
 * property read.
 */

import { resolveConfig, type PaymentFlowOptions, type ResolvedConfig } from './config.js';
import { Transport } from './transport.js';
import { Payments } from './resources/payments.js';
import { Refunds } from './resources/refunds.js';
import { Balance, BalanceTransactions } from './resources/balance.js';
import { Events } from './resources/events.js';
import { Analytics, RequestLogs, Usage } from './resources/reporting.js';
import { WebhookDeliveries, WebhookEndpoints } from './resources/webhooks.js';
import { TestHelpers } from './resources/test-helpers.js';

export class PaymentFlow {
  /** The configuration this client resolved, after defaults and validation. */
  readonly config: ResolvedConfig;

  /** Creating, reading and moving payments through their lifecycle. */
  readonly payments: Payments;
  /** Reading refunds. They are created through `payments.refund()`. */
  readonly refunds: Refunds;
  /** Your current balance, per currency. */
  readonly balance: Balance;
  /** The entries that moved your balance. */
  readonly balanceTransactions: BalanceTransactions;
  /** The event log behind your webhooks. */
  readonly events: Events;
  /** Payment activity summarized over a window. */
  readonly analytics: Analytics;
  /** Your API calls, as the platform recorded them. */
  readonly requestLogs: RequestLogs;
  /** Your API usage, metered per UTC day. */
  readonly usage: Usage;
  /** Where events are delivered, and their signing secrets. */
  readonly webhookEndpoints: WebhookEndpoints;
  /** What happened when an event was delivered. */
  readonly webhookDeliveries: WebhookDeliveries;
  /** The sandbox controls. Test mode only, decided by your key. */
  readonly testHelpers: TestHelpers;

  constructor(options: PaymentFlowOptions = {}) {
    this.config = resolveConfig(options);
    const transport = new Transport(this.config);

    this.payments = new Payments(transport);
    this.refunds = new Refunds(transport);
    this.balance = new Balance(transport);
    this.balanceTransactions = new BalanceTransactions(transport);
    this.events = new Events(transport);
    this.analytics = new Analytics(transport);
    this.requestLogs = new RequestLogs(transport);
    this.usage = new Usage(transport);
    this.webhookEndpoints = new WebhookEndpoints(transport);
    this.webhookDeliveries = new WebhookDeliveries(transport);
    this.testHelpers = new TestHelpers(transport);
  }
}
