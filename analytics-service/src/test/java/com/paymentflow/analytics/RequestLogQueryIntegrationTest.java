package com.paymentflow.analytics;

import com.paymentflow.analytics.dto.RequestLogResponse;
import com.paymentflow.analytics.dto.UsageSummaryResponse;
import com.paymentflow.analytics.event.ApiRequestEventPayload;
import com.paymentflow.analytics.service.ApiRequestLogService;
import com.paymentflow.analytics.service.ApiUsageRollupService;
import com.paymentflow.analytics.service.RequestLogQueryService;
import com.paymentflow.common.dto.page.CursorPage;
import com.paymentflow.common.dto.event.EventEnvelope;
import com.paymentflow.common.exception.BadRequestException;
import com.paymentflow.common.query.CursorCodec;
import com.paymentflow.common.query.ListQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M20.6 against real Postgres. The isolation sweep is the load-bearing part: this is a log of
 * every request a merchant made, including redacted bodies, so a scoping mistake here leaks more
 * than any other read surface on the platform.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "paymentflow.request-log.partition-initial-delay-ms=3600000",
        "paymentflow.request-log.rollup-initial-delay-ms=3600000",
        "paymentflow.request-log.retention-initial-delay-ms=3600000"
})
@Testcontainers
class RequestLogQueryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private ApiRequestLogService requestLogService;
    @Autowired
    private RequestLogQueryService queryService;
    @Autowired
    private ApiUsageRollupService rollupService;
    @Autowired
    private CursorCodec cursorCodec;

    private void record(UUID merchantId, String mode, Instant at, String path, int status, String method) {
        ApiRequestEventPayload payload = new ApiRequestEventPayload(
                merchantId, UUID.randomUUID(), mode, method, path, "limit=10", status, 25,
                "203.0.113.7", "curl/8", "corr-" + UUID.randomUUID(), "req-1",
                status >= 400 ? "ERR" : null, null, "{\"object\":\"list\"}",
                Map.of("Accept", "application/json", "Authorization", "[REDACTED]"));
        requestLogService.record(new EventEnvelope<>(
                UUID.randomUUID(), "ApiRequestCompleted", merchantId.toString(), at, payload.correlationId(),
                mode, payload));
    }

    private ListQuery query(int limit, String startingAfter, UUID merchantId, String mode) {
        return ListQuery.resolve(limit, startingAfter, null, null, cursorCodec, merchantId, mode);
    }

    @Test
    @DisplayName("the log returns a merchant's own requests, newest first, in the list envelope")
    void listsOwnRequests() {
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now();
        record(merchantId, "test", now.minusSeconds(30), "/v1/payments", 200, "GET");
        record(merchantId, "test", now.minusSeconds(10), "/v1/refunds", 404, "GET");

        CursorPage<RequestLogResponse> page =
                queryService.listRequestLogs(merchantId, "test", query(25, null, merchantId, "test"), null, null);

        assertThat(page.object()).isEqualTo(CursorPage.OBJECT_TYPE);
        assertThat(page.data()).hasSize(2);
        assertThat(page.data().getFirst().path()).isEqualTo("/v1/refunds");
        assertThat(page.data().getFirst().object()).isEqualTo(RequestLogResponse.OBJECT_TYPE);
        assertThat(page.data().getFirst().requestHeaders()).containsEntry("Authorization", "[REDACTED]");
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    @DisplayName("another merchant's requests are invisible, and so are the other mode's")
    void isScopedToMerchantAndMode() {
        // The sweep that matters most on this endpoint: the request log carries paths, query
        // strings and redacted bodies, so a scoping mistake leaks more here than anywhere else.
        UUID merchantId = UUID.randomUUID();
        UUID otherMerchant = UUID.randomUUID();
        Instant now = Instant.now();
        record(merchantId, "test", now, "/v1/payments", 200, "GET");
        record(otherMerchant, "test", now, "/v1/payments", 200, "GET");
        record(merchantId, "live", now, "/v1/payments", 200, "GET");

        assertThat(queryService.listRequestLogs(merchantId, "test", query(25, null, merchantId, "test"), null, null)
                .data()).hasSize(1);
        assertThat(queryService.listRequestLogs(otherMerchant, "test", query(25, null, otherMerchant, "test"), null, null)
                .data()).hasSize(1);
        assertThat(queryService.listRequestLogs(merchantId, "live", query(25, null, merchantId, "live"), null, null)
                .data()).hasSize(1);
    }

    @Test
    @DisplayName("a cursor from another merchant is refused before it reaches the query")
    void refusesAForgedCursor() {
        UUID merchantId = UUID.randomUUID();
        UUID otherMerchant = UUID.randomUUID();
        // Two rows, so the first page genuinely has a next cursor — with only one row the page
        // is complete, nextCursor is null, and the test would assert nothing at all.
        record(merchantId, "test", Instant.now().minusSeconds(5), "/v1/payments", 200, "GET");
        record(merchantId, "test", Instant.now(), "/v1/payments", 200, "GET");

        String theirCursor = queryService
                .listRequestLogs(merchantId, "test", query(1, null, merchantId, "test"), null, null)
                .nextCursor();
        assertThat(theirCursor).isNotNull();

        assertThatThrownBy(() -> query(25, theirCursor, otherMerchant, "test"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("pagination returns every request exactly once across page boundaries")
    void paginatesWithoutGapsOrDuplicates() {
        UUID merchantId = UUID.randomUUID();
        Instant base = Instant.now().minusSeconds(600);
        for (int i = 0; i < 7; i++) {
            record(merchantId, "test", base.plusSeconds(i), "/v1/payments", 200, "GET");
        }

        Set<UUID> seen = new HashSet<>();
        List<UUID> ordered = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 10; guard++) {
            CursorPage<RequestLogResponse> page =
                    queryService.listRequestLogs(merchantId, "test", query(2, cursor, merchantId, "test"), null, null);
            page.data().forEach(row -> {
                assertThat(seen.add(row.id())).as("row %s returned twice", row.id()).isTrue();
                ordered.add(row.id());
            });
            if (!page.hasMore()) {
                break;
            }
            cursor = page.nextCursor();
        }

        assertThat(seen).hasSize(7);
        assertThat(ordered).hasSize(7);
    }

    @Test
    @DisplayName("status and method filters narrow the list")
    void filters() {
        UUID merchantId = UUID.randomUUID();
        Instant now = Instant.now();
        record(merchantId, "test", now.minusSeconds(3), "/v1/payments", 200, "GET");
        record(merchantId, "test", now.minusSeconds(2), "/v1/payments", 429, "GET");
        record(merchantId, "test", now.minusSeconds(1), "/v1/payments", 200, "POST");

        assertThat(queryService.listRequestLogs(merchantId, "test", query(25, null, merchantId, "test"), 429, null)
                .data()).hasSize(1);
        assertThat(queryService.listRequestLogs(merchantId, "test", query(25, null, merchantId, "test"), null, "post")
                .data()).hasSize(1);
        // A filter that matches nothing returns nothing — failing open would be far worse.
        assertThat(queryService.listRequestLogs(merchantId, "test", query(25, null, merchantId, "test"), 418, null)
                .data()).isEmpty();
    }

    @Test
    @DisplayName("usage reports totals and per-route buckets with a derived mean")
    void reportsUsage() {
        UUID merchantId = UUID.randomUUID();
        LocalDate day = LocalDate.now(Clock.systemUTC()).minusDays(1);
        Instant at = day.atTime(10, 0).toInstant(ZoneOffset.UTC);
        record(merchantId, "test", at, "/v1/payments", 200, "GET");
        record(merchantId, "test", at, "/v1/payments", 500, "GET");
        rollupService.rollUp(day);

        UsageSummaryResponse usage = queryService.usage(merchantId, "test", day, day);

        assertThat(usage.totalRequests()).isEqualTo(2);
        assertThat(usage.totalServerErrors()).isEqualTo(1);
        // Two buckets, not one: the aggregate is per key as well as per route (§5/M20 asks for
        // usage "per key, per endpoint, per day"), and each request here used a different key.
        // This assertion originally expected one bucket and is what exposed the missing keyId
        // on the response — without it the two rows were indistinguishable to a caller.
        assertThat(usage.buckets()).hasSize(2);
        assertThat(usage.buckets()).allSatisfy(bucket -> {
            assertThat(bucket.route()).isEqualTo("/v1/payments");
            assertThat(bucket.keyId()).isNotNull();
            assertThat(bucket.meanDurationMs()).isEqualTo(25);
            assertThat(bucket.p95DurationMs()).isNotNull();
        });
        assertThat(usage.buckets()).extracting(UsageSummaryResponse.UsageBucketResponse::keyId)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("usage is scoped, so another merchant's totals never appear")
    void usageIsScoped() {
        UUID merchantId = UUID.randomUUID();
        UUID otherMerchant = UUID.randomUUID();
        LocalDate day = LocalDate.now(Clock.systemUTC()).minusDays(1);
        Instant at = day.atTime(11, 0).toInstant(ZoneOffset.UTC);
        record(merchantId, "test", at, "/v1/payments", 200, "GET");
        record(otherMerchant, "test", at, "/v1/payments", 200, "GET");
        rollupService.rollUp(day);

        assertThat(queryService.usage(merchantId, "test", day, day).totalRequests()).isEqualTo(1);
        assertThat(queryService.usage(otherMerchant, "test", day, day).totalRequests()).isEqualTo(1);
        // A merchant with no traffic gets an empty report, not a 404 — having no usage is a
        // fact, not a missing resource (the same reading M19.4 applied to an empty balance).
        assertThat(queryService.usage(UUID.randomUUID(), "test", day, day).totalRequests()).isZero();
    }

    @Test
    @DisplayName("an over-long or inverted usage window is rejected rather than truncated")
    void boundsTheUsageWindow() {
        UUID merchantId = UUID.randomUUID();
        LocalDate today = LocalDate.now(Clock.systemUTC());

        assertThatThrownBy(() -> queryService.usage(merchantId, "test", today.minusDays(120), today))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("90");
        assertThatThrownBy(() -> queryService.usage(merchantId, "test", today, today.minusDays(5)))
                .isInstanceOf(BadRequestException.class);
    }
}
