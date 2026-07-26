package com.paymentflow.notification.service;

import com.paymentflow.notification.config.WebhookProperties;
import com.paymentflow.notification.domain.AttemptOutcome;
import com.paymentflow.notification.domain.DeliveryStatus;
import com.paymentflow.notification.domain.EndpointDisableReason;
import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.domain.WebhookDeliveryAttempt;
import com.paymentflow.notification.domain.WebhookEndpoint;
import com.paymentflow.notification.domain.WebhookEvent;
import com.paymentflow.notification.email.EmailMessage;
import com.paymentflow.notification.email.EmailSender;
import com.paymentflow.notification.repository.WebhookDeliveryAttemptRepository;
import com.paymentflow.notification.repository.WebhookDeliveryRepository;
import com.paymentflow.notification.repository.WebhookEndpointRepository;
import com.paymentflow.notification.repository.WebhookEventRepository;
import com.paymentflow.notification.sandbox.SandboxScenarioClient;
import com.paymentflow.notification.sandbox.SandboxWebhookScenario;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs one delivery attempt end to end (M18.6): loads the delivery and everything it
 * needs, calls {@link WebhookDeliveryExecutor}, records the attempt, and updates both the
 * delivery's state and the endpoint's consecutive-failure streak.
 *
 * <p>The HTTP call happens <em>outside</em> any transaction — the same rule V1's D46
 * established and the reason its first attempt was made post-commit. Here the row reads
 * and the row writes are separate short transactions with the network call between them,
 * so a slow endpoint never holds a pooled database connection.
 *
 * <p>Both transactions are opened with an explicit {@link TransactionTemplate} rather
 * than {@code @Transactional}, because {@link #process} calls them from inside the same
 * bean: a self-invocation does not pass through the Spring proxy, so the annotations
 * would be silently inert and the "no network call inside a transaction" guarantee would
 * be the opposite of what the code claimed. {@code NotificationService} uses the same
 * template for the same reason (see {@code TransactionTemplateConfig}).
 *
 * <p>Idempotent by construction: a redelivery of the same dispatch message finds the
 * delivery already {@code DELIVERED} or {@code DEAD_LETTERED} and returns without making
 * a second call. Kafka is at-least-once (D2), so this is a requirement, not a nicety.
 */
@Service
public class WebhookDeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryProcessor.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryAttemptRepository attemptRepository;
    private final WebhookEventRepository eventRepository;
    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryExecutor executor;
    private final WebhookRetrySchedule retrySchedule;
    private final WebhookRetryRelay retryRelay;
    private final SandboxScenarioClient sandboxScenarioClient;
    private final WebhookProperties properties;
    private final EmailSender emailSender;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;

    public WebhookDeliveryProcessor(WebhookDeliveryRepository deliveryRepository,
                                    WebhookDeliveryAttemptRepository attemptRepository,
                                    WebhookEventRepository eventRepository,
                                    WebhookEndpointRepository endpointRepository,
                                    WebhookDeliveryExecutor executor, WebhookRetrySchedule retrySchedule,
                                    WebhookRetryRelay retryRelay, SandboxScenarioClient sandboxScenarioClient,
                                    WebhookProperties properties,
                                    EmailSender emailSender, MeterRegistry meterRegistry,
                                    TransactionTemplate transactionTemplate) {
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.eventRepository = eventRepository;
        this.endpointRepository = endpointRepository;
        this.executor = executor;
        this.retrySchedule = retrySchedule;
        this.retryRelay = retryRelay;
        this.sandboxScenarioClient = sandboxScenarioClient;
        this.properties = properties;
        this.emailSender = emailSender;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = transactionTemplate;
    }

    /** What happened, so the caller (dispatch now, retry listener in M18.7) can decide what comes next. */
    public enum Result {
        DELIVERED,
        /** Failed, and another attempt is scheduled. */
        FAILED,
        /** Failed with the published schedule exhausted — no further attempt will be made. */
        DEAD_LETTERED,
        /** Already resolved, or its delivery/event/endpoint no longer exists — nothing to do. */
        SKIPPED
    }

    public Result process(UUID deliveryId) {
        Optional<Context> maybeContext = load(deliveryId);
        if (maybeContext.isEmpty()) {
            return Result.SKIPPED;
        }
        Context context = maybeContext.get();

        // D131 (M17.5 defined these; M18.8 enacts them). Read before the attempt so a
        // sandbox outage costs one short timeout and then behaves normally, rather than
        // affecting the delivery itself.
        Optional<SandboxWebhookScenario> scenario = sandboxScenarioClient.activeScenario(
                context.event().getMerchantId(), context.event().getMode());

        int attemptNumber = (int) attemptRepository.countByDeliveryId(deliveryId) + 1;
        // The network call sits between two short transactions, never inside one.
        WebhookDeliveryAttempt attempt =
                executor.attempt(context.delivery(), context.event(), context.endpoint(), attemptNumber);

        if (scenario.filter(SandboxWebhookScenario.DUPLICATE_WEBHOOKS::equals).isPresent()) {
            // A genuine second request with its own signature and its own logged attempt —
            // the developer is proving their consumer is idempotent on event.id (§8.3), and
            // a duplicate they cannot see in the delivery log would be indistinguishable
            // from a platform bug. Sent before the first attempt is recorded so both land
            // on the wire together, which is the shape a real duplicate takes.
            WebhookDeliveryAttempt duplicate =
                    executor.attempt(context.delivery(), context.event(), context.endpoint(), attemptNumber + 1);
            attemptRepository.save(duplicate);
            meterRegistry.counter("webhook_simulated_duplicates_total").increment();
        }
        if (scenario.filter(SandboxWebhookScenario.WEBHOOK_FAILURE::equals).isPresent()) {
            // Overridden *after* the real call, not instead of it: the endpoint still
            // receives the delivery, and what the developer is exercising is this
            // platform's retry schedule and their own alerting — not their endpoint's
            // ability to return an error, which they can already test by returning one.
            attempt = WebhookDeliveryAttempt.transportFailed(deliveryId, attemptNumber,
                    context.endpoint().getUrl(), attempt.getRequestHeaders(), attempt.getRequestBody(),
                    attempt.getDurationMs() == null ? 0 : attempt.getDurationMs(),
                    "Simulated delivery failure (sandbox webhook_failure override).");
            meterRegistry.counter("webhook_simulated_failures_total").increment();
        }

        Outcome outcome = record(deliveryId, context.endpoint().getId(), attempt);

        // Both side effects happen after the bookkeeping transaction has committed, never
        // inside it: a failure here must never undo the record of what already happened
        // on the wire.
        if (outcome.result() == Result.DEAD_LETTERED) {
            deliveryRepository.findById(deliveryId).ifPresent(retryRelay::deadLetter);
        }
        // Only on the transition — the transaction is the one place that can tell "this
        // attempt disabled it" from "it was already disabled", so a repeatedly-failing
        // endpoint is announced once rather than on every attempt.
        if (outcome.autoDisabled()) {
            notifyAutoDisabled(context.endpoint().getId());
        }
        return outcome.result();
    }

    /** Reads everything the attempt needs in one short transaction, before any network I/O. */
    private Optional<Context> load(UUID deliveryId) {
        return transactionTemplate.execute(status -> loadWithin(deliveryId));
    }

    private Optional<Context> loadWithin(UUID deliveryId) {
        Optional<WebhookDelivery> maybeDelivery = deliveryRepository.findById(deliveryId);
        if (maybeDelivery.isEmpty()) {
            log.warn("No webhook delivery {} — dropping the dispatch message", deliveryId);
            return Optional.empty();
        }
        WebhookDelivery delivery = maybeDelivery.get();
        if (delivery.getStatus() != DeliveryStatus.PENDING) {
            // Already resolved by a prior message; this redelivery is an idempotent no-op.
            return Optional.empty();
        }
        if (delivery.getWebhookEventId() == null || delivery.getEndpointId() == null) {
            // A pre-M18.6 row from V1's single-URL path. Those are owned by the legacy
            // retry listener and must not be picked up here.
            return Optional.empty();
        }

        Optional<WebhookEvent> event = eventRepository.findById(delivery.getWebhookEventId());
        Optional<WebhookEndpoint> endpoint = endpointRepository.findById(delivery.getEndpointId());
        if (event.isEmpty() || endpoint.isEmpty()) {
            log.warn("Delivery {} references a missing event or endpoint — dropping", deliveryId);
            return Optional.empty();
        }
        if (!endpoint.get().isEnabled()) {
            // Disabled between fan-out and dispatch. Not an error and not a failure: the
            // merchant asked us to stop, so it must not count against the endpoint's streak.
            log.debug("Endpoint {} is disabled — skipping delivery {}", endpoint.get().getId(), deliveryId);
            return Optional.empty();
        }
        return Optional.of(new Context(delivery, event.get(), endpoint.get()));
    }

    /**
     * Persists the attempt and its consequences in one short transaction after the call
     * returns. The endpoint's streak is updated here rather than in the executor so that
     * "what happened on the wire" and "what it means for this endpoint" stay separable.
     */
    private Outcome record(UUID deliveryId, UUID endpointId, WebhookDeliveryAttempt attempt) {
        return transactionTemplate.execute(status -> recordWithin(deliveryId, endpointId, attempt));
    }

    private Outcome recordWithin(UUID deliveryId, UUID endpointId, WebhookDeliveryAttempt attempt) {
        attemptRepository.save(attempt);

        WebhookDelivery delivery = deliveryRepository.findById(deliveryId).orElseThrow();
        WebhookEndpoint endpoint = endpointRepository.findById(endpointId).orElseThrow();

        if (attempt.getOutcome() == AttemptOutcome.SUCCEEDED) {
            delivery.markDelivered();
            endpoint.recordDeliverySuccess();
            deliveryRepository.save(delivery);
            endpointRepository.save(endpoint);
            return new Outcome(Result.DELIVERED, false);
        }

        delivery.recordFailedAttempt();
        endpoint.recordDeliveryFailure();

        // M18.7: schedule the next attempt, or dead-letter when the published schedule is
        // exhausted. Recorded on the row before anything is published, so a crash between
        // the two leaves a delivery the sweeper can pick up rather than one that is lost.
        Optional<Instant> nextAttempt = retrySchedule.nextAttemptAt(delivery.getAttemptCount(), Instant.now());
        boolean deadLettered = nextAttempt.isEmpty();
        if (deadLettered) {
            delivery.markDeadLettered();
        } else {
            delivery.scheduleNextAttemptAt(nextAttempt.get());
        }

        // Auto-disable after N *consecutive* failures across distinct events (§4.5) —
        // this is what stops the platform spending its retry budget on an endpoint that
        // has been dead for a week.
        boolean autoDisabled = false;
        if (endpoint.isEnabled()
                && endpoint.getConsecutiveFailureCount() >= properties.autoDisableAfterConsecutiveFailures()) {
            endpoint.autoDisable(EndpointDisableReason.CONSECUTIVE_FAILURES, Instant.now());
            autoDisabled = true;
        }

        deliveryRepository.save(delivery);
        endpointRepository.save(endpoint);

        return new Outcome(deadLettered ? Result.DEAD_LETTERED : Result.FAILED, autoDisabled);
    }

    /**
     * What the bookkeeping transaction concluded. Auto-disable is carried separately from
     * the result because the two coincide: the attempt that exhausts the schedule can also
     * be the one that crosses the failure threshold, and collapsing them into a single
     * enum would silently drop one of the two notifications.
     */
    private record Outcome(Result result, boolean autoDisabled) {
    }

    /**
     * Notifies the merchant that the platform switched their endpoint off. Sent through
     * the existing {@link EmailSender} seam (D45's simulated transport, unchanged) —
     * an endpoint we disabled is exactly what a merchant must not have to discover from
     * missing traffic.
     *
     * <p>Deliberately <b>outside</b> the bookkeeping transaction. It was originally
     * inside it, and a failing insert there rolled back the recorded attempt and the
     * scheduled retry along with it, so a dead endpoint retried forever without ever
     * advancing. A notification is a side effect of the state change, never a
     * precondition for recording it.
     *
     * <p>A missing contact address skips the email rather than blocking the disable: the
     * endpoint being off is the load-bearing outcome; telling someone about it is best
     * effort.
     */
    private void notifyAutoDisabled(UUID endpointId) {
        endpointRepository.findById(endpointId).ifPresent(endpoint -> {
            meterRegistry.counter("webhook_endpoints_auto_disabled_total").increment();
            log.warn("Auto-disabled webhook endpoint {} after {} consecutive failures",
                    endpoint.getId(), endpoint.getConsecutiveFailureCount());
            if (endpoint.getContactEmail() == null || endpoint.getContactEmail().isBlank()) {
                log.warn("No contact address on endpoint {} — auto-disable notification skipped", endpointId);
                return;
            }
            emailSender.send(new EmailMessage(UUID.randomUUID(), endpoint.getMerchantId(), endpoint.getMode(),
                    endpoint.getContactEmail(), "Your webhook endpoint has been disabled",
                    "We disabled " + endpoint.getUrl() + " after " + endpoint.getConsecutiveFailureCount()
                            + " consecutive delivery failures. Fix the endpoint and re-enable it from the dashboard "
                            + "or the API; events are not queued while it is disabled.",
                    "WebhookEndpointAutoDisabled"));
        });
    }

    /** The three rows one attempt needs, read together before the call. */
    private record Context(WebhookDelivery delivery, WebhookEvent event, WebhookEndpoint endpoint) {
    }
}
