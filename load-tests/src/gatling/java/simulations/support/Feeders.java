package simulations.support;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Infinite feeders generating fresh unique values per virtual-user iteration —
 * a real email per registration (identity-service's `unique(email)` constraint
 * would otherwise collide across iterations) and a real UUID per
 * Idempotency-Key (D34 requires one on every payment mutation; reusing one
 * across genuinely different requests would trip the platform's own
 * key-reuse-conflict guard, D-precedent).
 *
 * <p>Gatling pulls from a shared feeder concurrently from every virtual user
 * an {@code atOnceUsers}/{@code rampUsers} injection starts in parallel, so
 * the returned {@code Iterator} must itself be safe for concurrent
 * {@code next()} calls. {@code Stream.generate(...).iterator()} is explicitly
 * documented as non-thread-safe (found the hard way: {@code atOnceUsers(10)}
 * hitting a shared {@code idempotencyKeyFeeder()} corrupted which key each
 * virtual user actually received, producing false idempotency-replay
 * failures — reproduced as correct via a manual curl call outside Gatling,
 * which is what proved the bug was here and not in payment-service).
 */
public final class Feeders {

    private Feeders() {
    }

    private static final AtomicLong EMAIL_SEQUENCE = new AtomicLong();

    public static Iterator<Map<String, Object>> emailFeeder() {
        return synchronizedInfiniteIterator(() -> Map.of(
                "email", "loadtest-" + System.nanoTime() + "-" + EMAIL_SEQUENCE.incrementAndGet() + "@example.com"
        ));
    }

    public static Iterator<Map<String, Object>> idempotencyKeyFeeder() {
        return synchronizedInfiniteIterator(() -> Map.of(
                "idempotencyKey", UUID.randomUUID().toString()
        ));
    }

    private static Iterator<Map<String, Object>> synchronizedInfiniteIterator(Supplier<Map<String, Object>> next) {
        return new Iterator<>() {
            @Override
            public synchronized boolean hasNext() {
                return true;
            }

            @Override
            public synchronized Map<String, Object> next() {
                return next.get();
            }
        };
    }
}
