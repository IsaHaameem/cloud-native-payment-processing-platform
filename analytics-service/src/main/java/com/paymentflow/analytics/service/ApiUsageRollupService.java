package com.paymentflow.analytics.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Rolls a completed day of {@code api_request_log} into {@code api_usage_daily} (M20.4,
 * §5/M20 task 5, D116).
 *
 * <p><b>This runs before the pruner and is what makes pruning safe.</b> The raw log keeps 30
 * days; the aggregates keep everything. A day that has not been rolled up must never be
 * dropped, which is why {@code api_usage_rollup_state} records completion explicitly rather
 * than letting retention infer it from the presence of aggregate rows — a day with no traffic
 * legitimately produces no rows, and is otherwise indistinguishable from a day whose rollup
 * never ran.
 */
@Service
public class ApiUsageRollupService {

    private static final Logger log = LoggerFactory.getLogger(ApiUsageRollupService.class);

    /**
     * Normalises ids out of a path so the aggregate groups by <em>route</em>.
     *
     * <p>Without this every {@code /v1/payments/<uuid>} is its own group and the table grows
     * one row per request — the opposite of an aggregate. Three shapes are collapsed: UUIDs,
     * the platform's prefixed public ids ({@code evt_}, {@code pay_}, …), and bare numeric
     * segments. Done in SQL rather than Java so the whole rollup stays a single pass over the
     * day's partition instead of streaming every row into the application.
     */
    private static final String ROUTE_EXPRESSION = """
            regexp_replace(
              regexp_replace(
                regexp_replace(path,
                  '/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}', '/{id}', 'g'),
                '/(evt|pay|ref|whep|whd|req)_[A-Za-z0-9]+', '/{id}', 'g'),
              '/[0-9]+(?=/|$)', '/{id}', 'g')
            """;

    private static final String ROLLUP_SQL = """
            insert into analytics.api_usage_daily (
                merchant_id, key_id, mode, day, route,
                request_count, client_error_count, server_error_count,
                total_duration_ms, max_duration_ms,
                p50_duration_ms, p95_duration_ms, p99_duration_ms)
            select
                merchant_id,
                key_id,
                mode,
                ?::date as day,
                %s as route,
                count(*),
                count(*) filter (where status_code between 400 and 499),
                count(*) filter (where status_code >= 500),
                coalesce(sum(duration_ms), 0),
                coalesce(max(duration_ms), 0),
                percentile_cont(0.50) within group (order by duration_ms)::bigint,
                percentile_cont(0.95) within group (order by duration_ms)::bigint,
                percentile_cont(0.99) within group (order by duration_ms)::bigint
            from analytics.api_request_log
            where occurred_at >= ?::date and occurred_at < (?::date + interval '1 day')
            group by merchant_id, key_id, mode, %s
            on conflict on constraint uq_api_usage_daily do update set
                request_count      = excluded.request_count,
                client_error_count = excluded.client_error_count,
                server_error_count = excluded.server_error_count,
                total_duration_ms  = excluded.total_duration_ms,
                max_duration_ms    = excluded.max_duration_ms,
                p50_duration_ms    = excluded.p50_duration_ms,
                p95_duration_ms    = excluded.p95_duration_ms,
                p99_duration_ms    = excluded.p99_duration_ms
            """.formatted(ROUTE_EXPRESSION, ROUTE_EXPRESSION);

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public ApiUsageRollupService(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this(jdbcTemplate, meterRegistry, Clock.systemUTC());
    }

    ApiUsageRollupService(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Rolls up yesterday, then any earlier day still missing. Hourly rather than once a day
     * for the same reason the partition manager is: a daily job gets one attempt, and a
     * missed one here blocks retention rather than merely delaying a report.
     *
     * <p>Re-running is harmless — the upsert recomputes a day from scratch — so catching up
     * needs no special path.
     */
    @Scheduled(
            initialDelayString = "${paymentflow.request-log.rollup-initial-delay-ms:90000}",
            fixedDelayString = "${paymentflow.request-log.rollup-interval-ms:3600000}")
    public void rollUpPendingDays() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        for (LocalDate day : pendingDays(today)) {
            try {
                rollUp(day);
            } catch (Exception e) {
                // One bad day must not stop the others: an un-rolled-up day blocks retention,
                // so silently giving up here would eventually fill the disk.
                meterRegistry.counter("api_usage_rollup_total", "outcome", "failure").increment();
                log.error("Failed to roll up API usage for {} — retention will hold this day's partition", day, e);
            }
        }
    }

    /**
     * Aggregates one whole day and records that it is done, in a single transaction. If the
     * insert succeeds but the state row does not, retention would drop rows nobody
     * aggregated — so the two cannot be allowed to disagree.
     */
    @Transactional
    public int rollUp(LocalDate day) {
        String iso = day.toString();
        int rows = jdbcTemplate.update(ROLLUP_SQL, iso, iso, iso);
        Long aggregated = jdbcTemplate.queryForObject("""
                select count(*) from analytics.api_request_log
                where occurred_at >= ?::date and occurred_at < (?::date + interval '1 day')
                """, Long.class, iso, iso);
        jdbcTemplate.update("""
                insert into analytics.api_usage_rollup_state (day, rows_aggregated, completed_at)
                values (?::date, ?, now())
                on conflict (day) do update set
                    rows_aggregated = excluded.rows_aggregated,
                    completed_at    = excluded.completed_at
                """, iso, aggregated == null ? 0 : aggregated);

        meterRegistry.counter("api_usage_rollup_total", "outcome", "success").increment();
        log.info("Rolled up API usage for {}: {} raw requests into {} usage rows", day, aggregated, rows);
        return rows;
    }

    /**
     * Every completed day not yet recorded as rolled up, oldest first, bounded by the raw
     * log's own earliest row so a fresh install does not walk backwards forever.
     *
     * <p>Today is deliberately excluded: it is still accumulating, and rolling it up would
     * publish a figure that changes under the reader.
     */
    private java.util.List<LocalDate> pendingDays(LocalDate today) {
        return jdbcTemplate.queryForList("""
                select distinct occurred_at::date as day
                from analytics.api_request_log
                where occurred_at < ?::date
                  and occurred_at::date not in (select day from analytics.api_usage_rollup_state)
                order by day
                """, LocalDate.class, today.toString());
    }
}
