package com.paymentflow.analytics.repository;

import com.paymentflow.analytics.domain.PaymentStatsHourly;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentStatsHourlyRepository extends JpaRepository<PaymentStatsHourly, UUID> {

    Optional<PaymentStatsHourly> findByMerchantIdAndCurrencyAndModeAndBucketStart(
            UUID merchantId, String currency, String mode, Instant bucketStart);

    /**
     * One merchant's series in one mode over a half-open range (M19.6), oldest first — a
     * chart reads left to right, unlike a log.
     *
     * <p>Not cursor-paginated, and that is deliberate rather than an omission: a time
     * series is bounded by its range, and the range is bounded by the API before this is
     * called. Cursors exist for append-heavy lists whose length is unknown (D107); a
     * bucket count is knowable in advance from the range, so pagination would add a
     * concept without removing a risk.
     */
    List<PaymentStatsHourly> findByMerchantIdAndModeAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
            UUID merchantId, String mode, Instant from, Instant to);
}
