package simulations.support;

import io.gatling.javaapi.core.FeederBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.gatling.javaapi.core.CoreDsl.csv;

/**
 * Feeds an already-seeded (token, merchantId) pair from data/merchants.csv
 * (SeedMerchantsSimulation must have run first) — the realistic-hot-path
 * entry point every payment-flow simulation after the seed step uses instead
 * of registering a new user per iteration.
 */
public final class MerchantFeeder {

    private MerchantFeeder() {
    }

    /** .circular() so a simulation with more iterations than seeded merchants wraps around rather than running dry. */
    public static FeederBuilder<String> circular() {
        return csv("data/merchants.csv").circular();
    }

    public static FeederBuilder<String> random() {
        return csv("data/merchants.csv").random();
    }

    /**
     * Only the first {@code n} seeded merchants, cycled continuously — deliberately
     * *not* the full pool, so many concurrent virtual users are forced onto the
     * same handful of merchants (ConcurrentContentionSimulation's whole point:
     * genuine optimistic-lock/idempotency contention, not spread-out traffic
     * that never actually contends on anything).
     */
    public static Iterator<Map<String, Object>> hotPool(int n) {
        List<Map<String, Object>> rows;
        try {
            rows = Files.lines(MerchantPool.CSV_PATH)
                    .skip(1) // header
                    .limit(n)
                    .map(line -> {
                        String[] parts = line.split(",", 2);
                        return Map.<String, Object>of("token", parts[0], "merchantId", parts[1]);
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "No rows in " + MerchantPool.CSV_PATH + " — run SeedMerchantsSimulation first.");
        }
        // Plain Stream-backed iterators aren't safe for the concurrent next() calls
        // every virtual user in this scenario's injection makes (see Feeders'
        // synchronizedInfiniteIterator javadoc for the failure this caused elsewhere).
        Iterator<Map<String, Object>> unsynchronized = Stream.generate(() -> rows).flatMap(List::stream).iterator();
        return new Iterator<>() {
            @Override
            public synchronized boolean hasNext() {
                return unsynchronized.hasNext();
            }

            @Override
            public synchronized Map<String, Object> next() {
                return unsynchronized.next();
            }
        };
    }
}
