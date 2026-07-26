package com.paymentflow.payment;

import com.paymentflow.common.dto.event.CanonicalEventType;
import com.paymentflow.common.query.ListQuery;
import com.paymentflow.common.query.MetadataFilterParams;
import com.paymentflow.payment.domain.PaymentStatus;
import com.paymentflow.payment.domain.RefundStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@code docs/READ_APIS.md} honest (M19.8) — the same discipline
 * {@code WebhookDocumentationConsistencyTest} applies to the webhook guide, for the same
 * reason: §10/R10 names documentation drift as fatal for a developer platform, and a
 * document that has quietly diverged from the platform is worse than no document because
 * it is trusted.
 *
 * <p>The read guide publishes concrete, relied-upon values — the default and maximum page
 * size, the filterable status vocabularies, the event-type vocabulary, and the exact
 * spelling of the metadata filter. Each is asserted against the code that implements it
 * rather than against a literal repeated in this test, so changing the platform without
 * updating the guide fails here rather than in an integrator's client.
 *
 * <p>Lives in payment-service because that is where most of the published vocabulary is
 * defined; analytics-service keeps its own equivalent for the window figures it owns.
 * Neither needs Spring or a database — these are values and a file.
 */
class ReadApiDocumentationConsistencyTest {

    /** Repo-root relative: the guide spans five services, so it does not belong under any one of them. */
    private static final Path GUIDE = Path.of("..", "docs", "READ_APIS.md");

    /**
     * The guide with runs of whitespace collapsed, so a published phrase split across a
     * line break still matches. Copied deliberately from the webhook guide's test — the
     * alternative trains people to loosen assertions rather than fix the document.
     */
    private static String guide() throws IOException {
        return Files.readString(GUIDE, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }

    @Test
    void theGuideExistsAndIsWhereTheDocsSiteWillLookForIt() {
        assertThat(GUIDE).exists();
    }

    @Test
    void thePublishedPageSizesMatchTheConfiguredOnes() throws IOException {
        String guide = guide();

        assertThat(ListQuery.DEFAULT_LIMIT).isEqualTo(25);
        assertThat(ListQuery.MAX_LIMIT).isEqualTo(100);
        // Both appear in the parameter table; a client sizing its own paging loop reads
        // these and nothing else.
        assertThat(guide).contains("| `limit` | " + ListQuery.DEFAULT_LIMIT + " |");
        assertThat(guide).contains("maximum of " + ListQuery.MAX_LIMIT);
    }

    @Test
    void everyFilterablePaymentStatusIsDocumented() throws IOException {
        String guide = guide();

        // Adding a status is a one-line change, and the guide is the only place a
        // merchant can learn the name exists in order to filter on it.
        for (PaymentStatus status : PaymentStatus.values()) {
            assertThat(guide)
                    .describedAs("payment status '%s' is missing from the read-API guide", status)
                    .contains("`" + status.name() + "`");
        }
    }

    @Test
    void everyFilterableRefundStatusIsDocumented() throws IOException {
        String guide = guide();

        for (RefundStatus status : RefundStatus.values()) {
            assertThat(guide)
                    .describedAs("refund status '%s' is missing from the read-API guide", status)
                    .contains("`" + status.name() + "`");
        }
    }

    @Test
    void everyEventTypeTheEventsApiCanReturnIsDocumented() throws IOException {
        String guide = guide();

        // The Events API filters on exactly this vocabulary, so a type absent from the
        // guide is a type nobody can discover in order to filter by it.
        for (CanonicalEventType type : CanonicalEventType.values()) {
            assertThat(guide)
                    .describedAs("event type '%s' is missing from the read-API guide", type.canonicalName())
                    .contains("`" + type.canonicalName() + "`");
        }
        assertThat(guide).contains(CanonicalEventType.ID_PREFIX);
    }

    @Test
    void thePublishedMetadataFilterSyntaxIsTheSyntaxTheApiActuallyParses() throws IOException {
        String published = "metadata[orderId]=A-1234";
        assertThat(guide()).contains(published);

        // Round-trips the exact string the guide prints back through the parser, rather
        // than asserting the document contains a literal this test also chose. The bug
        // M19.8 fixed was precisely a documented spelling the code did not accept.
        String[] parts = published.split("=", 2);
        assertThat(MetadataFilterParams.from(Map.of(parts[0], parts[1])))
                .containsExactly(Map.entry("orderId", "A-1234"));
    }

    @Test
    void thePublishedRangeSemanticsMatchTheImplementedOnes() throws IOException {
        String guide = guide();

        // Asserted because getting this backwards silently double-counts or skips rows
        // when a client walks a history a window at a time — and it is proven exclusive
        // in PaymentReadApiIntegrationTest.
        assertThat(guide).contains("`created_after` is **inclusive**, `created_before` is **exclusive**");
    }
}
