package dev.paymentflow;

import dev.paymentflow.internal.ClientConfig;
import dev.paymentflow.internal.Transport;
import dev.paymentflow.resources.Analytics;
import dev.paymentflow.resources.Balance;
import dev.paymentflow.resources.BalanceTransactions;
import dev.paymentflow.resources.Events;
import dev.paymentflow.resources.Payments;
import dev.paymentflow.resources.Refunds;
import dev.paymentflow.resources.RequestLogs;
import dev.paymentflow.resources.TestHelpers;
import dev.paymentflow.resources.Usage;
import dev.paymentflow.resources.WebhookDeliveries;
import dev.paymentflow.resources.WebhookEndpoints;

/**
 * The PaymentFlow API client.
 *
 * <pre>{@code
 * PaymentFlow client = PaymentFlow.builder()
 *     .apiKey(System.getenv("PAYMENTFLOW_API_KEY"))   // sk_test_… — server-side only
 *     .baseUrl("https://api.paymentflow.dev")
 *     .build();
 *
 * PaymentResponse payment = client.payments().create(
 *     Payments.params().amountMinor(1000).currency("USD")
 *         .description("Order A-1234").paymentMethodToken("pm_card_visa"));
 *
 * client.payments().authorize(payment.id());
 * PaymentResponse captured = client.payments().capture(payment.id());
 * }</pre>
 *
 * <p>One object holding one resolved configuration and one {@link java.net.http.HttpClient}, with
 * the eleven resource namespaces hanging off it. Build one per API key and share it — it is
 * cheap and thread-safe, but building one per request re-reads the environment and re-validates
 * for no benefit. Webhook verification lives on {@link Webhooks} as static methods, because a
 * receiver process often holds no API key and should not have to build a client to verify a
 * delivery.
 */
public final class PaymentFlow {

    private final ClientConfig config;

    private final Payments payments;
    private final Refunds refunds;
    private final Balance balance;
    private final BalanceTransactions balanceTransactions;
    private final Events events;
    private final Analytics analytics;
    private final RequestLogs requestLogs;
    private final Usage usage;
    private final WebhookEndpoints webhookEndpoints;
    private final WebhookDeliveries webhookDeliveries;
    private final TestHelpers testHelpers;

    /** Called by {@link PaymentFlowOptions.Builder#build()}. Resolves and validates the options. */
    PaymentFlow(PaymentFlowOptions options) {
        this.config = ClientConfig.resolve(options);
        Transport transport = new Transport(config);

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

    /** Starts building a client. */
    public static PaymentFlowOptions.Builder builder() {
        return PaymentFlowOptions.builder();
    }

    /** A client configured entirely from the environment ({@code PAYMENTFLOW_API_KEY}). */
    public static PaymentFlow fromEnvironment() {
        return builder().build();
    }

    /** Creating, reading and moving payments through their lifecycle. */
    public Payments payments() {
        return payments;
    }

    /** Reading refunds. They are created through {@code payments().refund()}. */
    public Refunds refunds() {
        return refunds;
    }

    /** Your current balance, per currency. */
    public Balance balance() {
        return balance;
    }

    /** The entries that moved your balance. */
    public BalanceTransactions balanceTransactions() {
        return balanceTransactions;
    }

    /** The event log behind your webhooks. */
    public Events events() {
        return events;
    }

    /** Payment activity summarized over a window. */
    public Analytics analytics() {
        return analytics;
    }

    /** Your API calls, as the platform recorded them. */
    public RequestLogs requestLogs() {
        return requestLogs;
    }

    /** Your API usage, metered per UTC day. */
    public Usage usage() {
        return usage;
    }

    /** Where events are delivered, and their signing secrets. */
    public WebhookEndpoints webhookEndpoints() {
        return webhookEndpoints;
    }

    /** What happened when an event was delivered. */
    public WebhookDeliveries webhookDeliveries() {
        return webhookDeliveries;
    }

    /** The sandbox controls. Test mode only, decided by your key. */
    public TestHelpers testHelpers() {
        return testHelpers;
    }

    /** The host this client calls. */
    public String baseUrl() {
        return config.baseUrl();
    }

    /** The dated API revision this client sends as {@code PaymentFlow-Version}. */
    public String apiVersion() {
        return config.apiVersion();
    }
}
