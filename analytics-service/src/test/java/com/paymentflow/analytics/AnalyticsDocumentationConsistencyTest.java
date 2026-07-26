package com.paymentflow.analytics;

import com.paymentflow.analytics.service.AnalyticsQueryService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The analytics half of {@code docs/READ_APIS.md}'s consistency guard (M19.8).
 *
 * <p>Separate from payment-service's {@code ReadApiDocumentationConsistencyTest} because
 * the numbers it protects are defined here: the default window a caller gets when they
 * omit a range, and the cap beyond which a request is refused. Both are figures an
 * integrator plans around — a client that batches a year of history into one call needs
 * the cap to be the number the guide says it is.
 */
class AnalyticsDocumentationConsistencyTest {

    private static final Path GUIDE = Path.of("..", "docs", "READ_APIS.md");

    private static String guide() throws IOException {
        return Files.readString(GUIDE, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }

    @Test
    void thePublishedDefaultWindowMatchesTheImplementedOne() throws IOException {
        assertThat(AnalyticsQueryService.DEFAULT_WINDOW).isEqualTo(Duration.ofDays(7));
        assertThat(guide()).contains("default to the last " + AnalyticsQueryService.DEFAULT_WINDOW.toDays() + " days");
    }

    @Test
    void thePublishedWindowCapMatchesTheEnforcedOne() throws IOException {
        assertThat(AnalyticsQueryService.MAX_WINDOW).isEqualTo(Duration.ofDays(90));
        assertThat(guide()).contains("may not exceed " + AnalyticsQueryService.MAX_WINDOW.toDays() + " days");
    }

    @Test
    void thePublishedSuccessRateDefinitionIsStatedExactly() throws IOException {
        String guide = guide();

        // There is more than one defensible denominator; publishing the formula is what
        // stops every client picking a different one, so the formula itself is asserted.
        assertThat(guide).contains("`authorizedCount / (authorizedCount + failedCount)`");
        // And the null case, which is the one a charting client gets wrong by default —
        // including the promise that the field is present rather than omitted, which the
        // live stack proved untrue before M19.8 removed the NON_NULL inclusion.
        assertThat(guide).contains("`successRate` is `null`, not `0`, when nothing was attempted");
        assertThat(guide).contains("always present, never omitted");
    }
}
