package com.paymentflow.common.query;

import java.time.Instant;
import java.util.UUID;

/**
 * The decoded position a cursor names (M19, D107): a {@code (createdAt, id)} pair.
 *
 * <p>A timestamp alone is not a stable position — two rows created in the same
 * millisecond would make the boundary ambiguous, and under load that is not a
 * hypothetical. Pairing it with the row's id gives a total order, and the keyset
 * predicate becomes {@code (created_at, id) < (:createdAt, :id)} for a descending list,
 * which is exactly what every M19 list query uses.
 *
 * <p>{@code merchantId} and {@code mode} are carried so the codec can bind a cursor to
 * the tenant that was issued it. They are not there to *scope the query* — every
 * repository method already takes both from the verified context and would ignore
 * anything a cursor claimed. They are there so a cursor lifted from one tenant and
 * presented by another is rejected outright rather than silently resolving to an empty
 * page, which is the difference between a clear error and a confusing one.
 */
public record Cursor(Instant createdAt, UUID id, UUID merchantId, String mode) {
}
