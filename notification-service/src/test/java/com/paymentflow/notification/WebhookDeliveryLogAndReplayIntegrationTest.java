package com.paymentflow.notification;

import com.paymentflow.common.security.InternalContextHeaders;
import com.paymentflow.common.security.InternalContextSigner;
import com.paymentflow.notification.crypto.WebhookSecretCipher;
import com.paymentflow.notification.domain.AttemptOutcome;
import com.paymentflow.notification.domain.DeliveryStatus;
import com.paymentflow.notification.domain.WebhookDelivery;
import com.paymentflow.notification.domain.WebhookDeliveryAttempt;
import com.paymentflow.notification.domain.WebhookEndpoint;
import com.paymentflow.notification.domain.WebhookEvent;
import com.paymentflow.notification.domain.WebhookSubscription;
import com.paymentflow.notification.repository.WebhookDeliveryAttemptRepository;
import com.paymentflow.notification.repository.WebhookDeliveryRepository;
import com.paymentflow.notification.repository.WebhookEndpointRepository;
import com.paymentflow.notification.repository.WebhookEventRepository;
import com.paymentflow.notification.repository.WebhookSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The delivery-log query API and manual replay over real HTTP (M18.8) — the half of this
 * milestone that makes the other half debuggable.
 *
 * <p>Rows are seeded directly rather than produced by driving Kafka: the delivery
 * *pipeline* is already proven end to end by {@code NotificationIntegrationTest}, and what
 * this suite is about is the read surface and the replay semantics. The Kafka
 * <b>listener</b> stays disabled so the assertions are about the API rather than about
 * timing — nothing is consumed here.
 *
 * <p><b>A broker is still required, and the reason is easy to miss.</b> Replay is not a pure
 * read: {@code WebhookDeliveryQueryService.replay} persists the new delivery and then calls
 * {@code WebhookDispatcher.dispatch}, which <em>produces</em> to {@code webhook.deliveries}.
 * Disabling the listener does not disable the producer. Without a broker,
 * {@code KafkaProducer.send} blocks for {@code max.block.ms} (60s by default) waiting for
 * cluster metadata and then throws, the endpoint returns 500, and the two replay tests fail.
 *
 * <p>This class originally declared only Postgres, so those two tests passed on a developer
 * machine purely because {@code application.yaml}'s default {@code localhost:59092} happened
 * to reach the docker-compose broker — an undeclared dependency on ambient local state, which
 * is exactly what CI does not have. Declaring the container makes the dependency explicit and
 * the suite self-contained, matching {@code NotificationIntegrationTest} and
 * {@code WebhookRetryAndAutoDisableIntegrationTest}, which both already do this.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "paymentflow.webhooks.require-https=false"
})
@AutoConfigureMockMvc
@Testcontainers
class WebhookDeliveryLogAndReplayIntegrationTest {

    private static final String PATH = "/v1/webhook_deliveries";
    private static final String SECRET = "dev-only-insecure-shared-secret-change-me";
    private static final String KEY_ID = UUID.randomUUID().toString();
    private static final String SCOPES = "webhooks:manage";
    private static final InternalContextSigner SIGNER = new InternalContextSigner();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    /**
     * Present so the producer inside {@code replay} has a broker to reach — see the class
     * javadoc. Nothing consumes from it: the listener is disabled above.
     */
    @Container
    static ConfluentKafkaContainer kafka =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    @DynamicPropertySource
    static void registerKafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private WebhookEndpointRepository endpointRepository;
    @Autowired
    private WebhookSubscriptionRepository subscriptionRepository;
    @Autowired
    private WebhookEventRepository eventRepository;
    @Autowired
    private WebhookDeliveryRepository deliveryRepository;
    @Autowired
    private WebhookDeliveryAttemptRepository attemptRepository;
    @Autowired
    private WebhookSecretCipher secretCipher;

    private record Seeded(WebhookEndpoint endpoint, WebhookEvent event, WebhookDelivery delivery) {
    }

    private Seeded seed(UUID merchantId, String mode, boolean withFailedAttempt) {
        WebhookEndpoint endpoint = endpointRepository.save(WebhookEndpoint.register(merchantId, mode,
                "http://sink.test/" + UUID.randomUUID(), "seeded", TestWebhookProperties.API_VERSION,
                secretCipher.encrypt("whsec_seed"), "whsec_seed", "ops@merchant.test"));
        subscriptionRepository.save(WebhookSubscription.of(endpoint.getId(), "*"));
        WebhookEvent event = eventRepository.save(WebhookEvent.of(UUID.randomUUID(), merchantId, mode,
                "payment.authorized", TestWebhookProperties.API_VERSION,
                "{\"object\":\"payment\",\"amountMinor\":5000}", Instant.now(), "corr-1"));
        WebhookDelivery delivery = deliveryRepository.save(WebhookDelivery.forEndpoint(
                event.getId(), endpoint.getId(), merchantId, mode, endpoint.getUrl()));
        if (withFailedAttempt) {
            attemptRepository.save(WebhookDeliveryAttempt.answered(delivery.getId(), 1, 502, endpoint.getUrl(),
                    "{\"PaymentFlow-Signature\":\"t=1,v1=aa\"}", "{\"id\":\"evt_seed\"}",
                    "{\"Content-Type\":\"text/plain\"}", "bad gateway", 37));
            delivery.recordFailedAttempt();
            deliveryRepository.save(delivery);
        }
        return new Seeded(endpoint, event, delivery);
    }

    @Test
    void theLogShowsTheFullRequestAndResponseForEveryAttempt() throws Exception {
        UUID merchantId = UUID.randomUUID();
        Seeded seeded = seed(merchantId, "test", true);

        mockMvc.perform(signed(get(PATH + "/" + seeded.delivery().getId()), merchantId, "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(seeded.event().getEventRef()))
                .andExpect(jsonPath("$.eventType").value("payment.authorized"))
                .andExpect(jsonPath("$.attemptCount").value(1))
                .andExpect(jsonPath("$.attempts.length()").value(1))
                // The milestone's own completion criterion: the *full* request and response,
                // not a summary. A merchant debugging a 502 needs the body their server sent.
                .andExpect(jsonPath("$.attempts[0].responseStatus").value(502))
                .andExpect(jsonPath("$.attempts[0].responseBody").value("bad gateway"))
                .andExpect(jsonPath("$.attempts[0].requestBody").value("{\"id\":\"evt_seed\"}"))
                .andExpect(jsonPath("$.attempts[0].durationMs").value(37))
                .andExpect(jsonPath("$.attempts[0].outcome").value("FAILED_STATUS"))
                // The signature we sent is shown deliberately: it is a signature, not a
                // secret, and comparing it against their own computation is the whole
                // debugging loop for a verification failure.
                .andExpect(jsonPath("$.attempts[0].requestHeaders")
                        .value(org.hamcrest.Matchers.containsString("PaymentFlow-Signature")));
    }

    @Test
    void theLogIsScopedToTheCallersOwnMerchantAndMode() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID otherMerchantId = UUID.randomUUID();
        Seeded seeded = seed(merchantId, "test", true);

        mockMvc.perform(signed(get(PATH + "/" + seeded.delivery().getId()), otherMerchantId, "test"))
                .andExpect(status().isNotFound());
        mockMvc.perform(signed(get(PATH + "/" + seeded.delivery().getId()), merchantId, "live"))
                .andExpect(status().isNotFound());

        mockMvc.perform(signed(get(PATH), merchantId, "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
        mockMvc.perform(signed(get(PATH), otherMerchantId, "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void replayCreatesANewDeliveryAndLeavesTheOriginalExactlyAsItWas() throws Exception {
        UUID merchantId = UUID.randomUUID();
        Seeded seeded = seed(merchantId, "test", true);
        UUID originalId = seeded.delivery().getId();

        String body = mockMvc.perform(signed(post(PATH + "/" + originalId + "/replay"), merchantId, "test"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayedFromDeliveryId").value(originalId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                // No attempts yet: it has been dispatched, not delivered. Reporting more
                // would be a promise the platform has not kept.
                .andExpect(jsonPath("$.attempts.length()").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"id\":\"" + originalId + "\"");

        // The original is untouched — a delivery log that mutates when you replay it
        // cannot answer "what happened the first time", which is usually the question.
        WebhookDelivery original = deliveryRepository.findById(originalId).orElseThrow();
        assertThat(original.getAttemptCount()).isEqualTo(1);
        assertThat(original.getReplayedFromDeliveryId()).isNull();
        assertThat(attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(originalId)).hasSize(1);

        // Both now appear in the log, as distinct deliveries.
        mockMvc.perform(signed(get(PATH), merchantId, "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void aReplayIsPermittedEvenWhenTheOriginalAlreadySucceeded() throws Exception {
        UUID merchantId = UUID.randomUUID();
        Seeded seeded = seed(merchantId, "test", false);
        WebhookDelivery delivered = seeded.delivery();
        delivered.markDelivered();
        deliveryRepository.save(delivered);

        // Re-sending a successful event is a legitimate operational action — a merchant
        // whose own consumer crashed after 200-ing needs exactly this.
        mockMvc.perform(signed(post(PATH + "/" + delivered.getId() + "/replay"), merchantId, "test"))
                .andExpect(status().isCreated());
    }

    @Test
    void replayingIntoADisabledEndpointIsRefusedWithAnActionableMessage() throws Exception {
        UUID merchantId = UUID.randomUUID();
        Seeded seeded = seed(merchantId, "test", true);
        WebhookEndpoint endpoint = seeded.endpoint();
        endpoint.disable();
        endpointRepository.save(endpoint);

        // Silently accepting would create a delivery the processor skips forever, leaving
        // a PENDING row that never resolves and no explanation anywhere.
        mockMvc.perform(signed(post(PATH + "/" + seeded.delivery().getId() + "/replay"), merchantId, "test"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Re-enable")));
    }

    @Test
    void replayingAnotherMerchantsDeliveryIsNotFound() throws Exception {
        UUID merchantId = UUID.randomUUID();
        Seeded seeded = seed(merchantId, "test", true);

        mockMvc.perform(signed(post(PATH + "/" + seeded.delivery().getId() + "/replay"),
                        UUID.randomUUID(), "test"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aLegacyPreFanOutDeliveryCannotBeReplayed() throws Exception {
        UUID merchantId = UUID.randomUUID();
        // A V1-shaped row: no canonical event, no endpoint — nothing coherent to re-send.
        WebhookDelivery legacy = deliveryRepository.save(WebhookDelivery.pending(
                UUID.randomUUID(), merchantId, "test", "http://sink.test/legacy", "{}"));

        mockMvc.perform(signed(post(PATH + "/" + legacy.getId() + "/replay"), merchantId, "test"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("predates")));
    }

    @Test
    void theLogIsNewestFirstAndPaginates() throws Exception {
        UUID merchantId = UUID.randomUUID();
        seed(merchantId, "test", true);
        seed(merchantId, "test", true);
        seed(merchantId, "test", true);

        mockMvc.perform(signed(get(PATH + "?page=0&size=2"), merchantId, "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));
    }

    @Test
    void anUnsignedRequestIsRejected() throws Exception {
        mockMvc.perform(get(PATH).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aDeliveryStatusAndAttemptOutcomeSurviveTheRoundTrip() throws Exception {
        UUID merchantId = UUID.randomUUID();
        Seeded seeded = seed(merchantId, "test", true);
        WebhookDelivery delivery = deliveryRepository.findById(seeded.delivery().getId()).orElseThrow();
        delivery.markDeadLettered();
        deliveryRepository.save(delivery);

        mockMvc.perform(signed(get(PATH + "/" + delivery.getId()), merchantId, "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(DeliveryStatus.DEAD_LETTERED.name()))
                .andExpect(jsonPath("$.attempts[0].outcome").value(AttemptOutcome.FAILED_STATUS.name()));
    }

    private static MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder builder, UUID merchantId,
                                                        String mode) {
        long issuedAt = Instant.now().getEpochSecond();
        String signature = SIGNER.sign(SECRET, merchantId.toString(), mode, KEY_ID, SCOPES, null, null, issuedAt);
        return builder
                .contentType(MediaType.APPLICATION_JSON)
                .header(InternalContextHeaders.MERCHANT_ID, merchantId.toString())
                .header(InternalContextHeaders.MODE, mode)
                .header(InternalContextHeaders.KEY_ID, KEY_ID)
                .header(InternalContextHeaders.SCOPES, SCOPES)
                .header(InternalContextHeaders.ISSUED_AT, String.valueOf(issuedAt))
                .header(InternalContextHeaders.SIGNATURE, signature);
    }
}
