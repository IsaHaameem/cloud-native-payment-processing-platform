package com.paymentflow.notification;

import com.paymentflow.notification.config.WebhookProperties;
import com.paymentflow.notification.domain.WebhookEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@code docs/WEBHOOKS.md} honest (M18.9).
 *
 * <p>The merchant-facing guide publishes concrete numbers — the retry schedule, the
 * auto-disable threshold, the rotation window, the timeout, the response cap — and the
 * whole point of publishing them is that an integrator can rely on them. A document that
 * quietly drifts from the configuration it describes is worse than no document, because it
 * is trusted. §10/R10 names documentation drift as fatal for a developer platform and
 * D115 answers it by executing samples in CI; this is the same discipline applied to the
 * one thing available now, which is the numbers.
 *
 * <p>Deliberately asserts against the <em>running configuration</em> rather than against
 * literals in the test, so changing {@code application.yaml} without updating the guide
 * fails here rather than in an integrator's retry logic. It does not attempt to execute
 * the code samples — that is M25's job, with the docs site and a live stack.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
class WebhookDocumentationConsistencyTest {

    private static final Path GUIDE = Path.of("docs", "WEBHOOKS.md");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private WebhookProperties properties;

    /**
     * The guide with runs of whitespace collapsed to single spaces.
     *
     * <p>Necessary rather than convenient: the guide is prose wrapped at 80 columns, so a
     * published phrase like "8 attempts over roughly 19 hours" is routinely split across a
     * line break. Matching raw text would make these assertions fail on a re-wrap — which
     * trains people to loosen the test rather than fix the document, and the document is
     * the thing being protected.
     */
    private static String guide() throws IOException {
        return Files.readString(GUIDE, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }

    @Test
    void theGuideExistsAndIsWhereTheDocsSiteWillLookForIt() {
        assertThat(GUIDE).exists();
    }

    @Test
    void thePublishedRetryScheduleMatchesTheConfiguredOne() throws IOException {
        String guide = guide();

        assertThat(properties.maxAttempts()).isEqualTo(8);
        assertThat(guide).contains("8 attempts over roughly 19 hours");
        // Every configured delay must appear in the published table. A schedule change
        // that skips the guide fails here rather than in a merchant's expectations.
        assertThat(guide).contains("| 2 | 5 seconds |");
        assertThat(guide).contains("| 3 | 30 seconds |");
        assertThat(guide).contains("| 4 | 2 minutes |");
        assertThat(guide).contains("| 5 | 10 minutes |");
        assertThat(guide).contains("| 6 | 1 hour |");
        assertThat(guide).contains("| 7 | 6 hours |");
        assertThat(guide).contains("| 8 | 12 hours |");

        Duration total = properties.retrySchedule().stream().reduce(Duration.ZERO, Duration::plus);
        assertThat(total).isEqualTo(Duration.ofHours(19).plusMinutes(12).plusSeconds(35));
    }

    @Test
    void thePublishedAutoDisableThresholdMatchesTheConfiguredOne() throws IOException {
        assertThat(properties.autoDisableAfterConsecutiveFailures()).isEqualTo(20);
        assertThat(guide()).contains("**20 consecutive failures across distinct events**");
    }

    @Test
    void thePublishedRotationWindowMatchesTheConfiguredOne() throws IOException {
        assertThat(properties.secretRotationGracePeriod()).isEqualTo(Duration.ofHours(48));
        assertThat(guide()).contains("next **48 hours**");
        assertThat(guide()).contains("After 48 hours the old secret stops being used");
    }

    @Test
    void thePublishedTimeoutAndResponseCapMatchTheConfiguredOnes() throws IOException {
        String guide = guide();

        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(guide).contains("no response within **5 seconds**");
        assertThat(properties.maxResponseBytes()).isEqualTo(8192);
        assertThat(guide).contains("read up to 8 KB");
    }

    @Test
    void thePublishedEndpointLimitMatchesTheConfiguredOne() throws IOException {
        assertThat(properties.maxEndpointsPerMode()).isEqualTo(16);
        assertThat(guide()).contains("up to 16 endpoints per mode");
    }

    @Test
    void everyEventTypeThePlatformCanEmitIsDocumented() throws IOException {
        String guide = guide();

        // The failure this prevents is specific and likely: adding a value to
        // WebhookEventType is a one-line change, and the guide is the only place a
        // merchant can learn the name exists in order to subscribe to it.
        for (WebhookEventType type : WebhookEventType.values()) {
            assertThat(guide)
                    .describedAs("event type '%s' is missing from the webhook guide", type.canonicalName())
                    .contains("`" + type.canonicalName() + "`");
        }
    }

    @Test
    void theSignatureSpecificationInTheGuideMatchesTheImplementation() throws IOException {
        String guide = guide();

        assertThat(guide).contains("signed_payload = \"{t}\" + \".\" + \"{the raw request body, byte for byte}\"");
        assertThat(guide).contains("lowercase hex HMAC-SHA256");
        // Stated in the guide because it is a plausible misreading that would silently
        // produce a wrong signature for every delivery (see WebhookSignerTest).
        assertThat(guide).contains("**is part of the key**");
        assertThat(guide).contains("PaymentFlow-Signature");
    }
}
