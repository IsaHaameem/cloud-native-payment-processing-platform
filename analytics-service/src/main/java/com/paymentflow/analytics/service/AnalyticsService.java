package com.paymentflow.analytics.service;

import com.paymentflow.analytics.domain.MerchantPaymentStats;
import com.paymentflow.analytics.domain.PaymentStatsHourly;
import com.paymentflow.analytics.domain.ProcessedEvent;
import com.paymentflow.analytics.event.AnalyticsEventPayload;
import com.paymentflow.analytics.repository.MerchantPaymentStatsRepository;
import com.paymentflow.analytics.repository.PaymentStatsHourlyRepository;
import com.paymentflow.analytics.repository.ProcessedEventRepository;
import com.paymentflow.common.dto.event.EventEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Updates the per-merchant/currency read-model aggregate for each payment lifecycle
 * event, idempotently (D2). Every event for a given merchant+currency contends on the
 * same {@code MerchantPaymentStats} row, so the whole event-processing transaction is
 * retried from scratch on an optimistic-lock or first-row-creation race — identical
 * shape to transaction-service's {@code LedgerService} (M6), same rationale: Postgres
 * aborts the rest of a transaction after a constraint violation, so a nested
 * catch-and-continue for a racing row creation isn't viable without a savepoint.
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private static final int MAX_ATTEMPTS = 10;
    private static final long BACKOFF_BASE_MILLIS = 20;
    // A null envelope mode (an in-flight pre-M16 message, or any not-yet-migrated producer)
    // is read as live — the null->live backfill contract every merchant-scoped table applies
    // to its pre-M16 rows (M16.1's EventEnvelope contract).
    private static final String DEFAULT_MODE = "live";

    private final ProcessedEventRepository processedEventRepository;
    private final MerchantPaymentStatsRepository merchantPaymentStatsRepository;
    private final PaymentStatsHourlyRepository paymentStatsHourlyRepository;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    public AnalyticsService(ProcessedEventRepository processedEventRepository,
                            MerchantPaymentStatsRepository merchantPaymentStatsRepository,
                            PaymentStatsHourlyRepository paymentStatsHourlyRepository,
                            TransactionTemplate transactionTemplate, MeterRegistry meterRegistry) {
        this.processedEventRepository = processedEventRepository;
        this.merchantPaymentStatsRepository = merchantPaymentStatsRepository;
        this.paymentStatsHourlyRepository = paymentStatsHourlyRepository;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;
    }

    public void processEvent(EventEnvelope<AnalyticsEventPayload> envelope) {
        for (int attempt = 1; ; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> processInTransaction(envelope));
                return;
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("Retrying event {} (attempt {}/{}) after {}", envelope.eventId(), attempt,
                        MAX_ATTEMPTS, e.getClass().getSimpleName());
                backoff(attempt);
            }
        }
    }

    private static void backoff(int attempt) {
        try {
            long jitterMillis = BACKOFF_BASE_MILLIS * attempt + (long) (Math.random() * BACKOFF_BASE_MILLIS);
            Thread.sleep(jitterMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void processInTransaction(EventEnvelope<AnalyticsEventPayload> envelope) {
        if (processedEventRepository.existsByEventId(envelope.eventId())) {
            log.debug("Event {} already processed, skipping", envelope.eventId());
            return;
        }

        AnalyticsEventPayload payload = envelope.payload();
        String mode = envelope.mode() == null ? DEFAULT_MODE : envelope.mode();
        MerchantPaymentStats stats = merchantPaymentStatsRepository
                .findByMerchantIdAndCurrencyAndMode(payload.merchantId(), payload.currency(), mode)
                .orElseGet(() -> MerchantPaymentStats.open(payload.merchantId(), payload.currency(), mode));

        // M19.6: the same event also lands in its hour's bucket. Read and written inside
        // this same transaction and the same retry loop, so the running total and the
        // series can never disagree about an event — one commit records both or neither.
        Instant bucketStart = PaymentStatsHourly.truncate(envelope.occurredAt());
        PaymentStatsHourly hourly = paymentStatsHourlyRepository
                .findByMerchantIdAndCurrencyAndModeAndBucketStart(
                        payload.merchantId(), payload.currency(), mode, bucketStart)
                .orElseGet(() -> PaymentStatsHourly.open(
                        payload.merchantId(), payload.currency(), mode, bucketStart));

        switch (payload.status()) {
            case "CREATED" -> {
                stats.incrementCreated();
                hourly.incrementCreated();
            }
            case "AUTHORIZED" -> {
                stats.incrementAuthorized();
                hourly.incrementAuthorized();
            }
            case "CAPTURED" -> {
                stats.incrementCaptured(payload.eventAmountMinor());
                hourly.incrementCaptured(payload.eventAmountMinor());
            }
            case "REFUNDED", "PARTIALLY_REFUNDED" -> {
                stats.incrementRefunded(payload.eventAmountMinor());
                hourly.incrementRefunded(payload.eventAmountMinor());
            }
            case "VOIDED" -> {
                stats.incrementVoided();
                hourly.incrementVoided();
            }
            // FAILED has no running-total counter (M7 never added one) and does have an
            // hourly one — a success rate needs a denominator that includes failures, and
            // that gap only became visible in M19 when something first read these numbers.
            case "FAILED" -> hourly.incrementFailed();
            default -> log.debug("No aggregate impact for status {} (event {})", payload.status(), envelope.eventId());
        }
        merchantPaymentStatsRepository.save(stats);
        paymentStatsHourlyRepository.save(hourly);

        processedEventRepository.save(ProcessedEvent.of(envelope.eventId(), envelope.eventType()));
        meterRegistry.counter("analytics_stats_updates_total", "eventType", envelope.eventType(),
                "currency", payload.currency()).increment();
    }
}
