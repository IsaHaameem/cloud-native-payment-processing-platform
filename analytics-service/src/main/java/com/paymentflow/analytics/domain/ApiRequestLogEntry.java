package com.paymentflow.analytics.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One recorded API request (M20.3).
 *
 * <p><b>Deliberately not a JPA entity</b>, unlike every other persistent type in this
 * service. Two reasons, and the second is the real one:
 *
 * <ul>
 *   <li>A partitioned table's unique constraints must include the partition key, so this
 *       table's primary key is {@code (id, occurred_at)}. Mapping that in JPA means an
 *       {@code @IdClass} or {@code @EmbeddedId} — real complexity bought for nothing, since
 *       nothing ever loads one of these rows by primary key.</li>
 *   <li>This is an append-only, highest-volume-on-the-platform log. It is written by exactly
 *       one code path, is never updated, never deleted row-by-row (M20.4's pruner drops whole
 *       partitions), and is never part of an object graph. Hibernate's identity map, dirty
 *       checking, and flush machinery are all pure overhead on that access pattern.</li>
 * </ul>
 *
 * <p>The aggregates this service already owns stay on JPA precisely because they <em>are</em>
 * read-modify-write rows with optimistic locking (M16.4), which is the opposite case.
 */
public record ApiRequestLogEntry(
        UUID id,
        UUID eventId,
        UUID merchantId,
        UUID keyId,
        String mode,
        String method,
        String path,
        String queryString,
        int statusCode,
        long durationMs,
        String clientIp,
        String userAgent,
        String correlationId,
        String requestId,
        String errorCode,
        String requestBody,
        String responseBody,
        String requestHeadersJson,
        Instant occurredAt) {
}
