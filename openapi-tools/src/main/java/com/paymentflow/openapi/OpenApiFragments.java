package com.paymentflow.openapi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes one service's OpenAPI fragment where the {@code mergeOpenApi} task will look for
 * it (M21.3).
 *
 * <p><b>Why a test writes this at all.</b> The document does not exist until springdoc has
 * scanned a running application context, so the only way to obtain a service's fragment is
 * to stand the service up — which each service's {@code OpenApiDocumentIntegrationTest}
 * already does, on Testcontainers, and already asserts is correct. Fetching it a second
 * time from a {@code bootRun}-ed service inside a Gradle task would mean a second way to
 * start six applications, with real Postgres, Redis and Kafka available at build time, to
 * produce a byte-identical result. The fragment is a by-product of a test that has to pass
 * anyway, and a fragment that was never asserted correct is not worth merging.
 *
 * <p>The directory comes from a system property the {@code openApiFragment} Gradle task
 * sets, with a default that works when the test is run from an IDE. Writing unconditionally
 * rather than only when the property is present keeps the behaviour the same in both — a
 * test that silently does nothing under one runner is how a stale fragment gets merged.
 */
public final class OpenApiFragments {

    /** Set by the {@code openApiFragment} task; the default is the module's own build dir. */
    public static final String DIRECTORY_PROPERTY = "paymentflow.openapi.fragmentDir";

    private static final String DEFAULT_DIRECTORY = "build/openapi";

    private OpenApiFragments() {
    }

    /**
     * Writes {@code document} as {@code <service>.json}.
     *
     * @param service  the module name, which becomes the file name and therefore the label
     *                 in every merge-conflict message — so it should read as the thing a
     *                 developer would go and fix ({@code notification-service}), not as an
     *                 abbreviation.
     * @param document the document exactly as the service served it. Written verbatim
     *                 rather than re-serialized: the merged baseline should be built from
     *                 what the API actually returns, and a re-render could differ from it
     *                 in whitespace or key order without anyone noticing.
     * @return the file written, so a caller can assert on it.
     */
    public static Path write(String service, String document) {
        Path file = Path.of(System.getProperty(DIRECTORY_PROPERTY, DEFAULT_DIRECTORY))
                .resolve(service + ".json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, document, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write the OpenAPI fragment for " + service, e);
        }
        return file;
    }
}
