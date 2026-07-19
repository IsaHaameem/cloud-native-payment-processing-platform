package simulations.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Thread-safe append-only writer for the seeded merchant-pool CSV
 * (SeedMerchantsSimulation writes it; every other simulation feeds from it
 * via {@code csv("data/merchants.csv")}). Concurrent virtual users in the
 * seed simulation all append at once — {@code synchronized} keeps each
 * append atomic, which a plain unsynchronized writer wouldn't guarantee.
 */
public final class MerchantPool {

    private MerchantPool() {
    }

    public static final Path CSV_PATH = Path.of("src/gatling/resources/data/merchants.csv");

    public static synchronized void reset() {
        try {
            Files.createDirectories(CSV_PATH.getParent());
            Files.write(CSV_PATH, List.of("token,merchantId"),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static synchronized void append(String token, String merchantId) {
        try {
            Files.write(CSV_PATH, List.of(token + "," + merchantId),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
