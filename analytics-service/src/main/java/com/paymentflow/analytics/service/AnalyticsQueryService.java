package com.paymentflow.analytics.service;

import com.paymentflow.analytics.domain.PaymentStatsHourly;
import com.paymentflow.analytics.dto.AnalyticsBucketResponse;
import com.paymentflow.analytics.dto.AnalyticsSummaryResponse;
import com.paymentflow.analytics.repository.PaymentStatsHourlyRepository;
import com.paymentflow.common.exception.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The analytics query API (M19.6, §5/M19 task 5) — volume, success rate, and totals,
 * time-bucketed, closing the other half of V1 known issue #4.
 *
 * <p>Read-only, and the aggregates it reads are written solely by the Kafka listener — so
 * like transaction-service and audit-service, M19 adds a way to read this service without
 * adding a way to write it.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsQueryService {

    /**
     * The default window when a caller supplies none: enough to be useful, small enough
     * to be cheap.
     *
     * <p>Public because it is a <em>published</em> figure —
     * {@code docs/READ_APIS.md} tells merchants what they get when they omit
     * {@code from}/{@code to}, and {@code AnalyticsDocumentationConsistencyTest} asserts
     * the guide against this constant rather than against a literal (M19.8).
     */
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(7);

    /**
     * The widest window a single request may span, in hourly buckets. 90 days is 2,160
     * buckets per currency — a large but bounded response. Without a cap this endpoint is
     * an unbounded query over a table that only grows, which is exactly what M19's risk
     * table warns about; a cap that rejects rather than silently truncates is the honest
     * form, because a silently shortened series would be charted as though it were the
     * whole story.
     *
     * <p>Public for the same reason as {@link #DEFAULT_WINDOW}: it is a number an
     * integrator plans around, so the guide's copy of it is asserted against this one.
     */
    public static final Duration MAX_WINDOW = Duration.ofDays(90);

    private final PaymentStatsHourlyRepository repository;

    public AnalyticsQueryService(PaymentStatsHourlyRepository repository) {
        this.repository = repository;
    }

    public AnalyticsSummaryResponse summary(UUID merchantId, String mode, Instant from, Instant to) {
        Instant end = (to == null) ? Instant.now() : to;
        Instant start = (from == null) ? end.minus(DEFAULT_WINDOW) : from;
        if (!start.isBefore(end)) {
            throw new BadRequestException("from must be earlier than to.");
        }
        if (Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw new BadRequestException("The requested window exceeds the maximum of "
                    + MAX_WINDOW.toDays() + " days.");
        }

        // Truncated to bucket boundaries so a caller asking for 09:30–10:30 gets the two
        // buckets that actually contain their data, rather than an empty series and no
        // explanation.
        Instant bucketFrom = PaymentStatsHourly.truncate(start);
        Instant bucketTo = PaymentStatsHourly.truncate(end).plus(Duration.ofHours(1));

        List<PaymentStatsHourly> buckets = repository
                .findByMerchantIdAndModeAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
                        merchantId, mode, bucketFrom, bucketTo);

        long created = 0;
        long authorized = 0;
        long captured = 0;
        long refunded = 0;
        long voided = 0;
        long failed = 0;
        long capturedAmount = 0;
        long refundedAmount = 0;
        List<AnalyticsBucketResponse> series = new java.util.ArrayList<>(buckets.size());

        for (PaymentStatsHourly bucket : buckets) {
            created += bucket.getCreatedCount();
            authorized += bucket.getAuthorizedCount();
            captured += bucket.getCapturedCount();
            refunded += bucket.getRefundedCount();
            voided += bucket.getVoidedCount();
            failed += bucket.getFailedCount();
            capturedAmount += bucket.getTotalCapturedAmountMinor();
            refundedAmount += bucket.getTotalRefundedAmountMinor();

            series.add(new AnalyticsBucketResponse(
                    AnalyticsBucketResponse.OBJECT_TYPE,
                    bucket.getBucketStart(),
                    bucket.getCurrency(),
                    bucket.getCreatedCount(),
                    bucket.getAuthorizedCount(),
                    bucket.getCapturedCount(),
                    bucket.getRefundedCount(),
                    bucket.getVoidedCount(),
                    bucket.getFailedCount(),
                    bucket.getTotalCapturedAmountMinor(),
                    bucket.getTotalRefundedAmountMinor()));
        }

        return new AnalyticsSummaryResponse(
                AnalyticsSummaryResponse.OBJECT_TYPE, bucketFrom, bucketTo,
                created, authorized, captured, refunded, voided, failed,
                capturedAmount, refundedAmount, successRate(authorized, failed), series);
    }

    /**
     * {@code authorized / (authorized + failed)} — how often an authorization attempt
     * succeeded.
     *
     * <p>Null rather than zero when nothing was attempted: a rate over zero attempts is
     * unknown, not zero, and charting it as zero would show a catastrophic outage every
     * quiet hour.
     */
    private static Double successRate(long authorized, long failed) {
        long attempts = authorized + failed;
        return attempts == 0 ? null : (double) authorized / attempts;
    }
}
