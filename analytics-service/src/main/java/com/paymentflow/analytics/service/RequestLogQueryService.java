package com.paymentflow.analytics.service;

import com.paymentflow.analytics.dto.RequestLogResponse;
import com.paymentflow.analytics.dto.UsageSummaryResponse;
import com.paymentflow.analytics.dto.UsageSummaryResponse.UsageBucketResponse;
import com.paymentflow.common.dto.page.CursorPage;
import com.paymentflow.common.exception.BadRequestException;
import com.paymentflow.common.query.Cursor;
import com.paymentflow.common.query.CursorCodec;
import com.paymentflow.common.query.ListQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads for {@code GET /v1/request_logs} and {@code GET /v1/usage} (M20.6, §5/M20 task 6).
 *
 * <p>Every query takes merchant and mode from the verified internal context and never from a
 * path or query parameter (D28/D101), so a cursor or filter a caller forges cannot widen the
 * range — the same structural rule every read surface added in M19 follows.
 *
 * <p>JDBC rather than JPA, for the reason {@code ApiRequestLogEntry} records: the log is not a
 * JPA entity, and the usage table is read with aggregate SQL that a repository interface would
 * only obscure.
 */
@Service
public class RequestLogQueryService {

    /**
     * The same 90-day ceiling M19.6 put on the analytics series, for the same reason: an
     * uncapped range over a per-day, per-route table is the unbounded query M20's risk table
     * warns about. Rejected rather than silently truncated — a shortened window would be
     * charted as though it were the whole story.
     */
    static final int MAX_USAGE_WINDOW_DAYS = 90;
    static final int DEFAULT_USAGE_WINDOW_DAYS = 30;

    private static final String SELECT_LOGS = """
            select id, key_id, mode, method, path, query_string, status_code, duration_ms,
                   client_ip, user_agent, correlation_id, request_id, error_code,
                   request_body, response_body, request_headers, occurred_at
            from analytics.api_request_log
            where merchant_id = ?
              and mode = ?
              and occurred_at >= ? and occurred_at <= ?
              and (?::int is null or status_code = ?::int)
              and (?::text is null or method = ?::text)
              -- Row-wise comparison, unguarded, so the keyset predicate stays an index
              -- condition rather than a filter (D141 — the defect M19.8 measured).
              and (occurred_at, id) < (?, ?)
            order by occurred_at desc, id desc
            limit ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final CursorCodec cursorCodec;
    private final ObjectMapper objectMapper;

    public RequestLogQueryService(JdbcTemplate jdbcTemplate, CursorCodec cursorCodec, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.cursorCodec = cursorCodec;
        this.objectMapper = objectMapper;
    }

    public CursorPage<RequestLogResponse> listRequestLogs(UUID merchantId, String mode, ListQuery query,
                                                          Integer statusCode, String method) {
        String normalisedMethod = method == null || method.isBlank() ? null : method.trim().toUpperCase(java.util.Locale.ROOT);

        List<RequestLogResponse> fetched = jdbcTemplate.query(SELECT_LOGS, rowMapper(mode),
                merchantId, mode,
                java.sql.Timestamp.from(query.createdAfterBound()),
                java.sql.Timestamp.from(query.createdBeforeBound()),
                statusCode, statusCode,
                normalisedMethod, normalisedMethod,
                java.sql.Timestamp.from(query.cursorCreatedAtBound()), query.cursorIdBound(),
                query.fetchSize());

        return CursorPage.of(fetched, query.limit(),
                row -> cursorCodec.encode(new Cursor(row.occurredAt(), row.id(), merchantId, mode)));
    }

    /**
     * Usage over a date range. Defaults to the last 30 days when unbounded, which is also the
     * raw log's retention window — asking for more than the platform keeps is a legitimate
     * question here, because {@code api_usage_daily} outlives the rows it was built from.
     */
    public UsageSummaryResponse usage(UUID merchantId, String mode, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(java.time.ZoneOffset.UTC);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_USAGE_WINDOW_DAYS - 1L);

        if (start.isAfter(end)) {
            throw new BadRequestException("`from` must not be after `to`.");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_USAGE_WINDOW_DAYS) {
            throw new BadRequestException(
                    "The usage window cannot exceed " + MAX_USAGE_WINDOW_DAYS + " days; requested " + days + ".");
        }

        List<UsageBucketResponse> buckets = jdbcTemplate.query("""
                select day, key_id, route, request_count, client_error_count, server_error_count,
                       total_duration_ms, max_duration_ms, p50_duration_ms, p95_duration_ms, p99_duration_ms
                from analytics.api_usage_daily
                where merchant_id = ? and mode = ? and day >= ? and day <= ?
                order by day desc, request_count desc
                """, (rs, rowNum) -> toBucket(rs), merchantId, mode, start, end);

        long totalRequests = buckets.stream().mapToLong(UsageBucketResponse::requests).sum();
        long clientErrors = buckets.stream().mapToLong(UsageBucketResponse::clientErrors).sum();
        long serverErrors = buckets.stream().mapToLong(UsageBucketResponse::serverErrors).sum();

        return new UsageSummaryResponse(start, end, totalRequests, clientErrors, serverErrors, buckets);
    }

    private static UsageBucketResponse toBucket(ResultSet rs) throws SQLException {
        long requests = rs.getLong("request_count");
        long totalDuration = rs.getLong("total_duration_ms");
        // Mean is derived from the sum and count the rollup kept for exactly this — and is null
        // rather than a division by zero when a bucket somehow has no requests.
        Long mean = requests > 0 ? totalDuration / requests : null;
        return new UsageBucketResponse(
                rs.getObject("day", LocalDate.class),
                rs.getObject("key_id", UUID.class),
                rs.getString("route"),
                requests,
                rs.getLong("client_error_count"),
                rs.getLong("server_error_count"),
                mean,
                nullableLong(rs, "p50_duration_ms"),
                nullableLong(rs, "p95_duration_ms"),
                nullableLong(rs, "p99_duration_ms"),
                rs.getLong("max_duration_ms"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private RowMapper<RequestLogResponse> rowMapper(String mode) {
        return (rs, rowNum) -> new RequestLogResponse(
                RequestLogResponse.OBJECT_TYPE,
                rs.getObject("id", UUID.class),
                rs.getObject("key_id", UUID.class),
                rs.getString("mode"),
                rs.getString("method"),
                rs.getString("path"),
                rs.getString("query_string"),
                rs.getInt("status_code"),
                rs.getLong("duration_ms"),
                rs.getString("client_ip"),
                rs.getString("user_agent"),
                rs.getString("correlation_id"),
                rs.getString("request_id"),
                rs.getString("error_code"),
                rs.getString("request_body"),
                rs.getString("response_body"),
                readHeaders(rs.getString("request_headers")),
                rs.getTimestamp("occurred_at").toInstant());
    }

    private Map<String, String> readHeaders(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            // A row whose headers will not parse must not cost the caller the whole entry —
            // the method, path, status and latency are what the log is mostly read for.
            return Map.of();
        }
    }
}
