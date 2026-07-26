package com.paymentflow.analytics.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import com.paymentflow.analytics.repository.ApiRequestLogRepository;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Keeps future daily partitions of {@code api_request_log} in existence (M20.3).
 *
 * <p><b>Why this class has to exist at all.</b> §5/M20 asks for a "daily-partitioned" log and
 * stops there, but Postgres declarative partitioning creates nothing on its own: a
 * range-partitioned table with no partition covering the incoming row does not spill or
 * degrade, it <em>rejects the insert</em>. A day-partitioned table shipped without a manager
 * therefore works perfectly until midnight and then stops recording — the kind of failure
 * that looks like a Kafka problem for the first hour of investigation.
 *
 * <p>Two independent defences, because this runs unattended: partitions are created
 * {@code LOOKAHEAD_DAYS} ahead so a missed tick is harmless, and the table additionally has a
 * DEFAULT partition so even a total failure of this component costs correctness nothing.
 *
 * <p>Idempotent by construction ({@code create table if not exists}), which is what makes it
 * safe to run on every instance rather than needing leader election — the same reasoning the
 * outbox relays already rely on.
 */
@Component
public class RequestLogPartitionManager {

    /** How far ahead partitions are kept. A week of missed ticks changes nothing. */
    public static final int LOOKAHEAD_DAYS = 7;

    private static final Logger log = LoggerFactory.getLogger(RequestLogPartitionManager.class);
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /** Explicit {@code @Autowired} because the clock-injecting constructor below makes two. */
    @Autowired
    public RequestLogPartitionManager(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this(jdbcTemplate, meterRegistry, Clock.systemUTC());
    }

    RequestLogPartitionManager(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * Runs hourly rather than daily. A daily job has exactly one chance to succeed before the
     * table it maintains starts rejecting writes; an hourly one has twenty-four, and costs a
     * handful of {@code if not exists} statements that do nothing.
     */
    @Scheduled(
            // A minute, not five seconds. There is nothing urgent to do at startup — V4
            // seeds a week of partitions — and firing immediately made every short-lived
            // test context log a connection failure as its database was torn down
            // underneath the scheduler. Noise that is explained away on every build is how
            // a real failure stops being noticed (D89).
            initialDelayString = "${paymentflow.request-log.partition-initial-delay-ms:60000}",
            fixedDelayString = "${paymentflow.request-log.partition-interval-ms:3600000}")
    public void ensurePartitions() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        for (int offset = 0; offset <= LOOKAHEAD_DAYS; offset++) {
            createPartitionFor(today.plusDays(offset));
        }
    }

    private void createPartitionFor(LocalDate day) {
        // Schema-qualified for the same reason as ApiRequestLogRepository: JDBC resolves
        // against search_path, not Hibernate's default_schema.
        String name = ApiRequestLogRepository.TABLE + "_" + day.format(SUFFIX);
        try {
            // Identifiers are derived from a formatted date, never from input, so there is no
            // injection surface here despite the string-built DDL that partitioning requires.
            jdbcTemplate.execute(String.format(
                    "create table if not exists %s partition of %s for values from ('%s') to ('%s')",
                    name, ApiRequestLogRepository.TABLE, day, day.plusDays(1)));
            meterRegistry.counter("api_request_log_partition_ensured_total", "outcome", "success").increment();
        } catch (Exception e) {
            // Never fatal: the DEFAULT partition means a failure here delays a housekeeping
            // benefit rather than losing a single request-log row.
            meterRegistry.counter("api_request_log_partition_ensured_total", "outcome", "failure").increment();
            log.error("Could not ensure request-log partition {} — rows will land in the default partition", name, e);
        }
    }
}
