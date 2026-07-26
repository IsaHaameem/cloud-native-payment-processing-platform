package com.paymentflow.analytics.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Drops {@code api_request_log} partitions past their retention window (M20.4, §4.6, D116).
 *
 * <p><b>Dropping partitions is the whole reason the table is partitioned.</b> A
 * {@code delete from api_request_log where occurred_at < …} on the busiest table in the
 * platform means hours of row-by-row deletion followed by vacuum pressure and no reclaimed
 * disk until it completes. {@code drop table} on a day partition is a catalogue update that
 * returns immediately and frees the space at once. M20.3 set this up; this class is where it
 * pays off.
 *
 * <p><b>Retention never runs ahead of the rollup.</b> Every candidate day must appear in
 * {@code api_usage_rollup_state} before its partition can be dropped. If the rollup is broken,
 * the log grows — which is a disk problem, visible and recoverable — rather than losing the
 * only copy of data nobody aggregated, which is not.
 */
@Service
public class RequestLogRetentionService {

    private static final Logger log = LoggerFactory.getLogger(RequestLogRetentionService.class);
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String PARTITION_PREFIX = "api_request_log_";

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final int retentionDays;

    @Autowired
    public RequestLogRetentionService(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry,
                                      @Value("${paymentflow.request-log.retention-days:30}") int retentionDays) {
        this(jdbcTemplate, meterRegistry, retentionDays, Clock.systemUTC());
    }

    /**
     * Explicit retention and clock — the seam tests use to exercise expiry without waiting
     * 30 days or mutating the system clock.
     */
    public RequestLogRetentionService(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry, int retentionDays,
                                      Clock clock) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("request-log retention-days must be at least 1");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        this.retentionDays = retentionDays;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${paymentflow.request-log.retention-initial-delay-ms:120000}",
            fixedDelayString = "${paymentflow.request-log.retention-interval-ms:3600000}")
    public void pruneExpiredPartitions() {
        LocalDate cutoff = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(retentionDays);
        for (String partition : expiredPartitions(cutoff)) {
            dropPartition(partition);
        }
    }

    /**
     * Partitions whose day is strictly older than the cutoff <em>and</em> already rolled up.
     *
     * <p>Derived from {@code pg_inherits} rather than from a date loop, so a partition created
     * by any means — the migration, the manager, or by hand during an incident — is considered.
     * The DEFAULT partition is excluded by name: it holds rows for arbitrary dates, so dropping
     * it would discard data no date test has cleared.
     */
    private List<String> expiredPartitions(LocalDate cutoff) {
        return jdbcTemplate.queryForList("""
                select child.relname
                from pg_inherits
                join pg_class parent on pg_inherits.inhparent = parent.oid
                join pg_class child  on pg_inherits.inhrelid  = child.oid
                where parent.relname = 'api_request_log'
                  and child.relname ~ '^api_request_log_[0-9]{8}$'
                order by child.relname
                """, String.class).stream()
                .filter(name -> isExpired(name, cutoff))
                .filter(this::isRolledUp)
                .toList();
    }

    private boolean isExpired(String partitionName, LocalDate cutoff) {
        try {
            return LocalDate.parse(partitionName.substring(PARTITION_PREFIX.length()), SUFFIX).isBefore(cutoff);
        } catch (Exception e) {
            // A partition whose name does not parse is not one this class created; leaving it
            // alone is the only safe reading of an unrecognised table.
            log.warn("Skipping unrecognised request-log partition {}", partitionName);
            return false;
        }
    }

    private boolean isRolledUp(String partitionName) {
        LocalDate day = LocalDate.parse(partitionName.substring(PARTITION_PREFIX.length()), SUFFIX);
        Long rolledUp = jdbcTemplate.queryForObject(
                "select count(*) from analytics.api_usage_rollup_state where day = ?::date",
                Long.class, day.toString());
        if (rolledUp == null || rolledUp == 0) {
            meterRegistry.counter("api_request_log_partitions_retained_total", "reason", "not_rolled_up").increment();
            log.warn("Partition {} is past retention but has not been rolled up — keeping it. "
                    + "The usage rollup needs attention; the log will keep growing until it runs.", partitionName);
            return false;
        }
        return true;
    }

    private void dropPartition(String partitionName) {
        try {
            // Name comes from pg_inherits and is regex-constrained above, never from input.
            jdbcTemplate.execute("drop table if exists analytics." + partitionName);
            meterRegistry.counter("api_request_log_partitions_dropped_total").increment();
            log.info("Dropped expired request-log partition {} (retention {} days)", partitionName, retentionDays);
        } catch (Exception e) {
            meterRegistry.counter("api_request_log_partitions_retained_total", "reason", "drop_failed").increment();
            log.error("Could not drop expired request-log partition {}", partitionName, e);
        }
    }
}
