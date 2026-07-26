package com.paymentflow.analytics;

import com.paymentflow.analytics.domain.PaymentStatsHourly;
import com.paymentflow.analytics.dto.AnalyticsSummaryResponse;
import com.paymentflow.analytics.repository.PaymentStatsHourlyRepository;
import com.paymentflow.analytics.service.AnalyticsQueryService;
import com.paymentflow.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The analytics query API (M19.6) against real Postgres — the series, the derived rate,
 * and the bound that keeps it from becoming an unbounded query.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
class AnalyticsQueryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private PaymentStatsHourlyRepository repository;
    @Autowired
    private AnalyticsQueryService analyticsQueryService;
    /** The application's own mapper, so the assertion reflects real serialization settings. */
    @Autowired
    private ObjectMapper objectMapper;

    private PaymentStatsHourly bucket(UUID merchantId, String mode, Instant at, int authorized, int failed,
                                      long capturedAmount) {
        PaymentStatsHourly hourly = PaymentStatsHourly.open(merchantId, "USD", mode, at);
        for (int i = 0; i < authorized; i++) {
            hourly.incrementAuthorized();
        }
        for (int i = 0; i < failed; i++) {
            hourly.incrementFailed();
        }
        if (capturedAmount > 0) {
            hourly.incrementCaptured(capturedAmount);
        }
        return repository.saveAndFlush(hourly);
    }

    @Test
    void theSummaryTotalsTheSeriesAndReturnsBothInOneResponse() {
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);
        bucket(merchantId, "test", now.minus(Duration.ofHours(2)), 3, 1, 1000);
        bucket(merchantId, "test", now.minus(Duration.ofHours(1)), 5, 0, 2500);

        AnalyticsSummaryResponse summary = analyticsQueryService.summary(merchantId, "test",
                now.minus(Duration.ofHours(3)), now);

        assertThat(summary.object()).isEqualTo("analytics_summary");
        assertThat(summary.authorizedCount()).isEqualTo(8);
        assertThat(summary.failedCount()).isEqualTo(1);
        assertThat(summary.totalCapturedAmountMinor()).isEqualTo(3500);
        // Series oldest-first: a chart reads left to right, unlike a log.
        assertThat(summary.buckets()).hasSize(2);
        assertThat(summary.buckets().getFirst().bucketStart()).isBefore(summary.buckets().getLast().bucketStart());
    }

    @Test
    void theSuccessRateUsesAttemptsAsItsDenominator() {
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);
        bucket(merchantId, "test", now, 3, 1, 0);

        AnalyticsSummaryResponse summary = analyticsQueryService.summary(merchantId, "test",
                now.minus(Duration.ofHours(1)), now);

        // authorized / (authorized + failed) — not over created, which would make the rate
        // fall simply because traffic arrived and had not been attempted yet.
        assertThat(summary.successRate()).isEqualTo(0.75);
    }

    @Test
    void aWindowWithNoAttemptsHasAnUnknownRateRatherThanZero() {
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);

        AnalyticsSummaryResponse summary = analyticsQueryService.summary(merchantId, "test",
                now.minus(Duration.ofHours(1)), now);

        // Zero would chart as a catastrophic outage every quiet hour.
        assertThat(summary.successRate()).isNull();
        assertThat(summary.buckets()).isEmpty();
    }

    @Test
    void theUnknownRateIsSerializedAsAnExplicitNullRatherThanOmitted() {
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);

        AnalyticsSummaryResponse summary = analyticsQueryService.summary(merchantId, "test",
                now.minus(Duration.ofHours(1)), now);

        // The assertion above passes on the object either way; what a client actually
        // receives is a different question, and before M19.8 the answer was wrong — the
        // response carried no successRate key at all. An absent field is what §4.10 tells
        // clients to expect from a *version* that lacks it, so silence here would hide the
        // one case the null exists to communicate. Found on the live stack, not in-process.
        assertThat(objectMapper.writeValueAsString(summary)).contains("\"successRate\":null");
    }

    @Test
    void theSeriesIsScopedToOneMerchantAndOneMode() {
        UUID merchantId = UUID.randomUUID();
        UUID otherMerchant = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);
        bucket(merchantId, "test", now, 2, 0, 100);
        bucket(merchantId, "live", now, 9, 0, 900);
        bucket(otherMerchant, "test", now, 7, 0, 700);

        Instant from = now.minus(Duration.ofHours(1));
        assertThat(analyticsQueryService.summary(merchantId, "test", from, now).authorizedCount()).isEqualTo(2);
        assertThat(analyticsQueryService.summary(merchantId, "live", from, now).authorizedCount()).isEqualTo(9);
        assertThat(analyticsQueryService.summary(otherMerchant, "test", from, now).authorizedCount()).isEqualTo(7);
    }

    @Test
    void anExcessiveWindowIsRejectedRatherThanSilentlyTruncated() {
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now();

        // A silently shortened series would be charted as though it were the whole story.
        assertThatThrownBy(() -> analyticsQueryService.summary(merchantId, "test",
                now.minus(Duration.ofDays(120)), now))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("90");
    }

    @Test
    void anInvertedWindowIsRejected() {
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatThrownBy(() -> analyticsQueryService.summary(merchantId, "test", now, now.minusSeconds(3600)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aPartialHourStillReturnsTheBucketThatContainsIt() {
        UUID merchantId = UUID.randomUUID();
        Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        bucket(merchantId, "test", hour, 1, 0, 500);

        // Asking for 09:30–09:45 must return the 09:00 bucket the data is in, rather than
        // an empty series and no explanation.
        AnalyticsSummaryResponse summary = analyticsQueryService.summary(merchantId, "test",
                hour.plus(Duration.ofMinutes(30)), hour.plus(Duration.ofMinutes(45)));

        assertThat(summary.buckets()).hasSize(1);
        assertThat(summary.authorizedCount()).isEqualTo(1);
    }
}
