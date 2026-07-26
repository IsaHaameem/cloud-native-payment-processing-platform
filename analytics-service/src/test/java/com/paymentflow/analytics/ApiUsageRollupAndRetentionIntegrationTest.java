package com.paymentflow.analytics;

import com.paymentflow.analytics.event.ApiRequestEventPayload;
import com.paymentflow.analytics.service.ApiRequestLogService;
import com.paymentflow.analytics.service.ApiUsageRollupService;
import com.paymentflow.analytics.service.RequestLogPartitionManager;
import com.paymentflow.analytics.service.RequestLogRetentionService;
import com.paymentflow.common.dto.event.EventEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M20.4 against real Postgres. The load-bearing guarantee is the ordering one: retention must
 * never drop a day the rollup has not recorded, because that loses the only copy of data
 * nobody aggregated.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        // Every scheduled component is driven explicitly here; letting the timers also fire
        // would make "which run produced this row?" ambiguous.
        "paymentflow.request-log.partition-initial-delay-ms=3600000",
        "paymentflow.request-log.rollup-initial-delay-ms=3600000",
        "paymentflow.request-log.retention-initial-delay-ms=3600000"
})
@Testcontainers
class ApiUsageRollupAndRetentionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private ApiRequestLogService requestLogService;
    @Autowired
    private ApiUsageRollupService rollupService;
    @Autowired
    private RequestLogPartitionManager partitionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MeterRegistry meterRegistry;

    private void record(UUID merchantId, UUID keyId, String mode, Instant at, String path, int status, long durationMs) {
        ApiRequestEventPayload payload = new ApiRequestEventPayload(
                merchantId, keyId, mode, "GET", path, null, status, durationMs,
                "203.0.113.7", "curl/8", "corr-" + UUID.randomUUID(), "req-1",
                status >= 400 ? "ERR" : null, null, null, Map.of());
        requestLogService.record(new EventEnvelope<>(
                UUID.randomUUID(), "ApiRequestCompleted", merchantId.toString(), at, payload.correlationId(),
                mode, payload));
    }

    private Map<String, Object> usageRow(UUID merchantId) {
        return jdbcTemplate.queryForMap(
                "select * from analytics.api_usage_daily where merchant_id = ?", merchantId);
    }

    @Test
    @DisplayName("a day rolls up into counts, split error classes, and exact percentiles")
    void rollsUpADay() {
        UUID merchantId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        LocalDate day = LocalDate.now(Clock.systemUTC()).minusDays(1);
        Instant at = day.atTime(10, 0).toInstant(ZoneOffset.UTC);

        // 10 requests: 7 ok, 2 client errors, 1 server error; durations 10..100.
        for (int i = 1; i <= 10; i++) {
            int status = i <= 7 ? 200 : (i <= 9 ? 404 : 500);
            record(merchantId, keyId, "test", at, "/v1/payments", status, i * 10L);
        }

        rollupService.rollUp(day);

        Map<String, Object> row = usageRow(merchantId);
        assertThat(row.get("route")).isEqualTo("/v1/payments");
        assertThat(row.get("request_count")).isEqualTo(10L);
        // Split rather than one error count: 4xx is the developer's problem, 5xx is ours.
        assertThat(row.get("client_error_count")).isEqualTo(2L);
        assertThat(row.get("server_error_count")).isEqualTo(1L);
        assertThat(row.get("total_duration_ms")).isEqualTo(550L);
        assertThat(row.get("max_duration_ms")).isEqualTo(100L);
        // Computed from the raw rows while they still exist — percentiles cannot be
        // recombined from aggregates afterwards, so this is the only moment they are knowable.
        assertThat((Long) row.get("p50_duration_ms")).isBetween(50L, 60L);
        assertThat((Long) row.get("p95_duration_ms")).isBetween(90L, 100L);
    }

    @Test
    @DisplayName("ids are normalised out of the path, so the aggregate is per route not per request")
    void normalisesIdsIntoRoutes() {
        // Without this the table grows one row per request, which is the opposite of an
        // aggregate — the single most important property of the rollup.
        UUID merchantId = UUID.randomUUID();
        LocalDate day = LocalDate.now(Clock.systemUTC()).minusDays(1);
        Instant at = day.atTime(11, 0).toInstant(ZoneOffset.UTC);

        record(merchantId, null, "test", at, "/v1/payments/" + UUID.randomUUID(), 200, 5);
        record(merchantId, null, "test", at, "/v1/payments/" + UUID.randomUUID(), 200, 5);
        record(merchantId, null, "test", at, "/v1/events/evt_2f8a9c1b4d6e", 200, 5);
        record(merchantId, null, "test", at, "/v1/events/evt_9a1c3e5f7b2d", 200, 5);

        rollupService.rollUp(day);

        Map<String, Long> byRoute = jdbcTemplate.query(
                "select route, request_count from analytics.api_usage_daily where merchant_id = ?",
                rs -> {
                    Map<String, Long> out = new java.util.HashMap<>();
                    while (rs.next()) {
                        out.put(rs.getString("route"), rs.getLong("request_count"));
                    }
                    return out;
                }, merchantId);

        assertThat(byRoute).containsOnlyKeys("/v1/payments/{id}", "/v1/events/{id}");
        assertThat(byRoute.get("/v1/payments/{id}")).isEqualTo(2);
        assertThat(byRoute.get("/v1/events/{id}")).isEqualTo(2);
    }

    @Test
    @DisplayName("re-running a rollup recomputes rather than double-counting")
    void isIdempotent() {
        UUID merchantId = UUID.randomUUID();
        LocalDate day = LocalDate.now(Clock.systemUTC()).minusDays(1);
        Instant at = day.atTime(12, 0).toInstant(ZoneOffset.UTC);
        record(merchantId, null, "live", at, "/v1/balance", 200, 20);

        rollupService.rollUp(day);
        rollupService.rollUp(day);
        rollupService.rollUp(day);

        assertThat(usageRow(merchantId).get("request_count")).isEqualTo(1L);
    }

    @Test
    @DisplayName("test and live usage aggregate separately")
    void separatesModes() {
        UUID merchantId = UUID.randomUUID();
        LocalDate day = LocalDate.now(Clock.systemUTC()).minusDays(1);
        Instant at = day.atTime(13, 0).toInstant(ZoneOffset.UTC);
        record(merchantId, null, "test", at, "/v1/payments", 200, 10);
        record(merchantId, null, "live", at, "/v1/payments", 200, 10);

        rollupService.rollUp(day);

        assertThat(jdbcTemplate.queryForList(
                "select mode from analytics.api_usage_daily where merchant_id = ? order by mode",
                String.class, merchantId)).containsExactly("live", "test");
    }

    @Test
    @DisplayName("a rolled-up partition past retention is dropped")
    void dropsExpiredPartitionsOnceRolledUp() {
        LocalDate oldDay = LocalDate.now(Clock.systemUTC()).minusDays(40);
        createPartitionFor(oldDay);
        UUID merchantId = UUID.randomUUID();
        record(merchantId, null, "test", oldDay.atTime(9, 0).toInstant(ZoneOffset.UTC), "/v1/payments", 200, 10);

        rollupService.rollUp(oldDay);
        assertThat(partitionExists(oldDay)).isTrue();

        retentionService(30).pruneExpiredPartitions();

        assertThat(partitionExists(oldDay)).isFalse();
        // The aggregate outlives the raw rows — that is the entire point of rolling up first.
        assertThat(usageRow(merchantId).get("request_count")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a partition past retention that was never rolled up is KEPT, not dropped")
    void refusesToDropUnrolledData() {
        // The guarantee the whole design turns on. If the rollup is broken the log grows —
        // a disk problem, visible and recoverable — rather than losing the only copy of data
        // nobody aggregated, which is not recoverable at all.
        LocalDate oldDay = LocalDate.now(Clock.systemUTC()).minusDays(45);
        createPartitionFor(oldDay);
        UUID merchantId = UUID.randomUUID();
        record(merchantId, null, "test", oldDay.atTime(9, 0).toInstant(ZoneOffset.UTC), "/v1/payments", 200, 10);

        // Deliberately no rollUp() call.
        retentionService(30).pruneExpiredPartitions();

        assertThat(partitionExists(oldDay))
                .as("an un-rolled-up partition must survive retention")
                .isTrue();
        assertThat(meterRegistry.counter("api_request_log_partitions_retained_total",
                "reason", "not_rolled_up").count()).isPositive();
    }

    @Test
    @DisplayName("a partition inside the retention window is untouched")
    void keepsPartitionsInsideTheWindow() {
        partitionManager.ensurePartitions();
        LocalDate today = LocalDate.now(Clock.systemUTC());

        retentionService(30).pruneExpiredPartitions();

        assertThat(partitionExists(today)).isTrue();
    }

    @Test
    @DisplayName("the DEFAULT partition is never dropped — it holds rows of arbitrary dates")
    void neverDropsTheDefaultPartition() {
        UUID merchantId = UUID.randomUUID();
        record(merchantId, null, "test", Instant.now().plus(400, ChronoUnit.DAYS), "/v1/payments", 200, 10);

        retentionService(1).pruneExpiredPartitions();

        Long exists = jdbcTemplate.queryForObject("""
                select count(*) from pg_class where relname = 'api_request_log_default'
                """, Long.class);
        assertThat(exists).isEqualTo(1);
    }

    private RequestLogRetentionService retentionService(int retentionDays) {
        return new RequestLogRetentionService(jdbcTemplate, meterRegistry, retentionDays, Clock.systemUTC());
    }

    private void createPartitionFor(LocalDate day) {
        String name = "analytics.api_request_log_" + day.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        jdbcTemplate.execute(String.format(
                "create table if not exists %s partition of analytics.api_request_log for values from ('%s') to ('%s')",
                name, day, day.plusDays(1)));
    }

    private boolean partitionExists(LocalDate day) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from pg_class where relname = ?", Long.class,
                "api_request_log_" + day.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")));
        return count != null && count > 0;
    }
}
