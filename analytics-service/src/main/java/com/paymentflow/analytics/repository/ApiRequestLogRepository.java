package com.paymentflow.analytics.repository;

import com.paymentflow.analytics.domain.ApiRequestLogEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;

/**
 * Writes to {@code api_request_log} (M20.3). JDBC rather than JPA — see
 * {@link ApiRequestLogEntry} for why.
 */
@Repository
public class ApiRequestLogRepository {

    /**
     * The schema is qualified explicitly on every statement in this class, following M19.2's
     * precedent and for the identical reason: Hibernate's {@code default_schema} applies to
     * JPA only, so a raw JDBC connection resolves against {@code search_path} and finds
     * nothing. M19.2 learned this on native queries; M20.3 learned it again the moment the
     * first JDBC-backed table appeared, which is why it is stated here rather than left to be
     * rediscovered a third time.
     */
    public static final String TABLE = "analytics.api_request_log";

    private static final String INSERT = """
            insert into analytics.api_request_log (
                id, event_id, merchant_id, key_id, mode, method, path, query_string,
                status_code, duration_ms, client_ip, user_agent, correlation_id, request_id,
                error_code, request_body, response_body, request_headers, occurred_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?)
            on conflict (event_id, occurred_at) do nothing
            """;

    private final JdbcTemplate jdbcTemplate;

    public ApiRequestLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts one request, ignoring a redelivery of an event already recorded.
     *
     * <p><b>{@code on conflict do nothing} is this consumer's entire idempotency mechanism</b>,
     * and it replaces the {@code processed_events} marker row every other consumer in this
     * platform writes (D2/M6). That pattern exists because those consumers perform a
     * <em>read-modify-write</em> on an aggregate — incrementing a total twice is invisible
     * afterwards, so the marker is the only way to know. This consumer performs a pure insert
     * of an immutable row that already carries the event id, so the table's own unique
     * constraint answers the same question with no second row, no second write, and no
     * possibility of the marker and the effect disagreeing.
     *
     * @return {@code true} if the row was newly recorded, {@code false} if it was a duplicate
     */
    public boolean insertIgnoringDuplicates(ApiRequestLogEntry entry) {
        int inserted = jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(INSERT);
            statement.setObject(1, entry.id());
            statement.setObject(2, entry.eventId());
            statement.setObject(3, entry.merchantId());
            statement.setObject(4, entry.keyId(), Types.OTHER);
            statement.setString(5, entry.mode());
            statement.setString(6, entry.method());
            statement.setString(7, entry.path());
            statement.setString(8, entry.queryString());
            statement.setInt(9, entry.statusCode());
            statement.setLong(10, entry.durationMs());
            statement.setString(11, entry.clientIp());
            statement.setString(12, entry.userAgent());
            statement.setString(13, entry.correlationId());
            statement.setString(14, entry.requestId());
            statement.setString(15, entry.errorCode());
            statement.setString(16, entry.requestBody());
            statement.setString(17, entry.responseBody());
            statement.setString(18, entry.requestHeadersJson());
            statement.setTimestamp(19, Timestamp.from(entry.occurredAt()));
            return statement;
        });
        return inserted > 0;
    }
}
