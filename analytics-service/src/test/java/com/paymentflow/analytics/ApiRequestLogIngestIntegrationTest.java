package com.paymentflow.analytics;

import com.paymentflow.analytics.event.ApiRequestEventPayload;
import com.paymentflow.analytics.service.ApiRequestLogService;
import com.paymentflow.analytics.service.RequestLogPartitionManager;
import com.paymentflow.common.dto.event.EventEnvelope;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M20.3 against real Postgres. The things worth proving here are the ones a mock cannot
 * show: that rows land in the right daily partition, that a redelivery does not double-count,
 * and that the partition manager keeps the table writable across a day boundary.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        // The manager is exercised explicitly below; letting it also fire on a timer would
        // make "which tick created this partition?" ambiguous.
        "paymentflow.request-log.partition-interval-ms=3600000",
        "paymentflow.request-log.partition-initial-delay-ms=3600000"
})
@Testcontainers
class ApiRequestLogIngestIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private ApiRequestLogService requestLogService;
    @Autowired
    private RequestLogPartitionManager partitionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static EventEnvelope<ApiRequestEventPayload> event(UUID merchantId, String mode, Instant occurredAt,
                                                               int status, String path) {
        ApiRequestEventPayload payload = new ApiRequestEventPayload(
                merchantId, UUID.randomUUID(), mode, "GET", path, "limit=10", status, 42,
                "203.0.113.7", "paymentflow-node/1.0", "corr-" + UUID.randomUUID(), "req-1",
                status >= 400 ? "CLIENT_ERROR" : null, null, "{\"object\":\"list\"}",
                Map.of("Accept", "application/json", "Authorization", "[REDACTED]"));
        return new EventEnvelope<>(UUID.randomUUID(), "ApiRequestCompleted", merchantId.toString(),
                occurredAt, payload.correlationId(), mode, payload);
    }

    private long countFor(UUID merchantId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from analytics.api_request_log where merchant_id = ?", Long.class, merchantId);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("a request event is recorded with its facts intact")
    void recordsARequest() {
        UUID merchantId = UUID.randomUUID();
        requestLogService.record(event(merchantId, "test", Instant.now(), 200, "/v1/payments"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select * from analytics.api_request_log where merchant_id = ?", merchantId);
        assertThat(row.get("mode")).isEqualTo("test");
        assertThat(row.get("method")).isEqualTo("GET");
        assertThat(row.get("path")).isEqualTo("/v1/payments");
        assertThat(row.get("status_code")).isEqualTo(200);
        assertThat(row.get("duration_ms")).isEqualTo(42L);
        assertThat(row.get("client_ip")).isEqualTo("203.0.113.7");
        assertThat(row.get("request_headers").toString()).contains("Accept");
    }

    @Test
    @DisplayName("a redelivered event is recorded once â€” the unique constraint is the dedupe")
    void isIdempotentUnderRedelivery() {
        // At-least-once delivery (D2) guarantees this happens. A request log that
        // double-counts is a usage-metering bug that becomes a billing bug the moment
        // anyone charges for it.
        UUID merchantId = UUID.randomUUID();
        EventEnvelope<ApiRequestEventPayload> envelope = event(merchantId, "live", Instant.now(), 200, "/v1/payments");

        requestLogService.record(envelope);
        requestLogService.record(envelope);
        requestLogService.record(envelope);

        assertThat(countFor(merchantId)).isEqualTo(1);
    }

    @Test
    @DisplayName("rows land in the daily partition covering their occurredAt")
    void routesRowsToTheRightDailyPartition() {
        UUID merchantId = UUID.randomUUID();
        Instant today = Instant.now();
        Instant tomorrow = today.plus(1, ChronoUnit.DAYS);

        requestLogService.record(event(merchantId, "test", today, 200, "/v1/payments"));
        requestLogService.record(event(merchantId, "test", tomorrow, 200, "/v1/refunds"));

        // tableoid resolves which physical partition each row actually occupies â€” the only
        // way to prove partitioning is working rather than assumed.
        List<String> partitions = jdbcTemplate.queryForList(
                "select tableoid::regclass::text from analytics.api_request_log where merchant_id = ? order by occurred_at",
                String.class, merchantId);

        assertThat(partitions).hasSize(2);
        assertThat(partitions.get(0)).isNotEqualTo(partitions.get(1));
        // regclass renders schema-qualified when search_path does not include the schema.
        assertThat(partitions).allSatisfy(name -> assertThat(name).contains("api_request_log_"));
        assertThat(partitions).noneMatch(name -> name.contains("default"));
    }

    @Test
    @DisplayName("the partition manager keeps a week of partitions ahead, idempotently")
    void maintainsFuturePartitions() {
        // The failure this prevents: a day-partitioned table with no partition for "now"
        // does not degrade, it rejects the insert â€” so the log would simply stop at midnight.
        partitionManager.ensurePartitions();
        long afterFirstRun = partitionCount();

        partitionManager.ensurePartitions();
        assertThat(partitionCount())
                .as("create table if not exists makes repeated runs safe on every instance")
                .isEqualTo(afterFirstRun);

        assertThat(afterFirstRun)
                .as("today plus %d days of lookahead", RequestLogPartitionManager.LOOKAHEAD_DAYS)
                .isGreaterThanOrEqualTo(RequestLogPartitionManager.LOOKAHEAD_DAYS + 1);
    }

    @Test
    @DisplayName("a far-future row lands in the default partition instead of being rejected")
    void theDefaultPartitionCatchesRowsNoPartitionCovers() {
        // The safety valve. Without it, a manager that fell behind would turn into silent
        // data loss at a date boundary rather than a housekeeping delay.
        UUID merchantId = UUID.randomUUID();
        requestLogService.record(event(merchantId, "test", Instant.now().plus(400, ChronoUnit.DAYS),
                200, "/v1/payments"));

        String partition = jdbcTemplate.queryForObject(
                "select tableoid::regclass::text from analytics.api_request_log where merchant_id = ?",
                String.class, merchantId);
        assertThat(partition).endsWith("api_request_log_default");
        assertThat(countFor(merchantId)).isEqualTo(1);
    }

    @Test
    @DisplayName("an event with no merchant is not recorded")
    void ignoresUnattributableEvents() {
        ApiRequestEventPayload payload = new ApiRequestEventPayload(
                null, null, "test", "GET", "/v1/payments", null, 401, 3, null, null, "corr", "req",
                "UNAUTHORIZED", null, null, null);
        EventEnvelope<ApiRequestEventPayload> envelope = new EventEnvelope<>(
                UUID.randomUUID(), "ApiRequestCompleted", "unattributed", Instant.now(), "corr", "test", payload);

        long before = totalCount();
        requestLogService.record(envelope);
        assertThat(totalCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("a null mode falls back to live, matching every merchant-scoped table")
    void defaultsAMissingModeToLive() {
        UUID merchantId = UUID.randomUUID();
        ApiRequestEventPayload payload = new ApiRequestEventPayload(
                merchantId, UUID.randomUUID(), null, "GET", "/v1/payments", null, 200, 5,
                null, null, "corr", "req", null, null, null, null);
        requestLogService.record(new EventEnvelope<>(
                UUID.randomUUID(), "ApiRequestCompleted", merchantId.toString(), Instant.now(), "corr", null, payload));

        assertThat(jdbcTemplate.queryForObject(
                "select mode from analytics.api_request_log where merchant_id = ?", String.class, merchantId))
                .isEqualTo("live");
    }

    private long partitionCount() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*) from pg_inherits
                join pg_class parent on pg_inherits.inhparent = parent.oid
                where parent.relname = 'api_request_log'
                """, Long.class);
        return count == null ? 0 : count;
    }

    private long totalCount() {
        Long count = jdbcTemplate.queryForObject("select count(*) from analytics.api_request_log", Long.class);
        return count == null ? 0 : count;
    }
}

