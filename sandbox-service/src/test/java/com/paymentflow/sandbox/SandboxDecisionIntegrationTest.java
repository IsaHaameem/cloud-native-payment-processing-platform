package com.paymentflow.sandbox;

import com.paymentflow.common.security.InternalContextHeaders;
import com.paymentflow.common.security.InternalContextSigner;
import com.paymentflow.sandbox.domain.Operation;
import com.paymentflow.sandbox.domain.ScheduledOutcome;
import com.paymentflow.sandbox.domain.SimulationScenario;
import com.paymentflow.sandbox.repository.DecisionLogRepository;
import com.paymentflow.sandbox.repository.ScheduledOutcomeRepository;
import com.paymentflow.sandbox.service.OverrideService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives {@code POST /internal/v1/sandbox/decisions} over real HTTP against real
 * Postgres, signing internal-context headers the same way payment-service's
 * {@code SandboxAuthorizationAdvisor} adapter will from M17.4 — this milestone builds
 * and proves the endpoint before any other service becomes a real caller.
 *
 * <p>The controller returns a {@code CompletableFuture} unconditionally (even for a
 * zero-latency decision, §6/M17.2's non-blocking-latency design), so every request
 * that reaches it — as opposed to being rejected earlier by security or validation —
 * goes through Spring MVC Test's two-step async dispatch, not a single {@code perform}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SandboxDecisionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private static final String SECRET = "dev-only-insecure-shared-secret-change-me";
    private static final String SERVICE_KEY_ID = UUID.randomUUID().toString();
    private static final String SERVICE_SCOPES = "internal:payment-service";
    private static final InternalContextSigner SIGNER = new InternalContextSigner();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DecisionLogRepository decisionLogRepository;

    @Autowired
    private OverrideService overrideService;

    @Autowired
    private ScheduledOutcomeRepository scheduledOutcomeRepository;

    @Test
    void missingInternalContextIsUnauthorized() throws Exception {
        mockMvc.perform(post("/internal/v1/sandbox/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decisionKey":"k-1","paymentId":"%s","operation":"AUTHORIZE",
                                 "amountMinor":1000,"currency":"USD"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noTokenFallsToModeDefaultApprove() throws Exception {
        UUID merchantId = UUID.randomUUID();
        performDecision(signedPost(merchantId, "test")
                        .content("""
                                {"decisionKey":"%s","paymentId":"%s","operation":"AUTHORIZE",
                                 "amountMinor":1000,"currency":"USD"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVE"))
                .andExpect(jsonPath("$.source").value("MODE_DEFAULT"));
    }

    @Test
    void knownDeclineCardDeclinesWithItsCode() throws Exception {
        UUID merchantId = UUID.randomUUID();
        performDecision(signedPost(merchantId, "test")
                        .content("""
                                {"decisionKey":"%s","paymentId":"%s","operation":"AUTHORIZE",
                                 "paymentMethodToken":"pm_card_chargeDeclined","amountMinor":1000,"currency":"USD"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DECLINE"))
                .andExpect(jsonPath("$.declineCode").value("card_declined"))
                .andExpect(jsonPath("$.source").value("TEST_CARD"));
    }

    @Test
    void decisionKeyReplayReturnsTheOriginalDecisionWithoutReevaluating() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String decisionKey = "replay-" + UUID.randomUUID();

        String firstBody = """
                {"decisionKey":"%s","paymentId":"%s","operation":"AUTHORIZE",
                 "paymentMethodToken":"pm_card_chargeDeclined","amountMinor":1000,"currency":"USD"}
                """.formatted(decisionKey, paymentId);
        performDecision(signedPost(merchantId, "test").content(firstBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DECLINE"));

        // A retried call with the SAME decision key but a different (approving) token
        // must still return the ORIGINAL decline — the log is authoritative, not the
        // second call's inputs.
        String retryBody = """
                {"decisionKey":"%s","paymentId":"%s","operation":"AUTHORIZE",
                 "paymentMethodToken":"pm_card_visa","amountMinor":1000,"currency":"USD"}
                """.formatted(decisionKey, paymentId);
        performDecision(signedPost(merchantId, "test").content(retryBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DECLINE"))
                .andExpect(jsonPath("$.declineCode").value("card_declined"));

        assertThat(decisionLogRepository.findByDecisionKey(decisionKey)).isPresent();
        long matching = decisionLogRepository.findAll().stream()
                .filter(e -> e.getDecisionKey().equals(decisionKey))
                .count();
        assertThat(matching).isEqualTo(1);
    }

    @Test
    void liveModeIgnoresADecliningTokenAndApproves() throws Exception {
        UUID merchantId = UUID.randomUUID();
        performDecision(signedPost(merchantId, "live")
                        .content("""
                                {"decisionKey":"%s","paymentId":"%s","operation":"AUTHORIZE",
                                 "paymentMethodToken":"pm_card_chargeDeclined","amountMinor":1000,"currency":"USD"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVE"))
                .andExpect(jsonPath("$.source").value("MODE_DEFAULT"));
    }

    @Test
    void unknownOperationIsBadRequest() throws Exception {
        UUID merchantId = UUID.randomUUID();
        mockMvc.perform(signedPost(merchantId, "test")
                        .content("""
                                {"decisionKey":"%s","paymentId":"%s","operation":"BOGUS",
                                 "amountMinor":1000,"currency":"USD"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void slowCardResponseIsDelayedByItsLatencyProfile() throws Exception {
        UUID merchantId = UUID.randomUUID();
        Instant start = Instant.now();
        performDecision(signedPost(merchantId, "test")
                        .content("""
                                {"decisionKey":"%s","paymentId":"%s","operation":"AUTHORIZE",
                                 "paymentMethodToken":"pm_card_slow","amountMinor":1000,"currency":"USD"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVE"))
                .andExpect(jsonPath("$.latencyMs").value(5000));

        assertThat(Instant.now()).isAfterOrEqualTo(start.plusMillis(4900));
    }

    @Test
    void anActiveForceDeclineOverrideBeatsAnApprovingCardAndIsConsumedExactlyOnce() throws Exception {
        UUID merchantId = UUID.randomUUID();
        overrideService.create(merchantId, "test", SimulationScenario.FORCE_DECLINE, "insufficient_funds", null,
                null, 1, null);

        performDecision(signedPost(merchantId, "test")
                        .content("""
                                {"decisionKey":"%s","paymentId":"%s","operation":"AUTHORIZE",
                                 "paymentMethodToken":"pm_card_visa","amountMinor":1000,"currency":"USD"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DECLINE"))
                .andExpect(jsonPath("$.declineCode").value("insufficient_funds"))
                .andExpect(jsonPath("$.source").value("OVERRIDE"));

        // remainingCount was 1 — exhausted by the call above, so the very next
        // AUTHORIZE decision for this merchant falls through to the card again.
        performDecision(signedPost(merchantId, "test")
                        .content("""
                                {"decisionKey":"%s","paymentId":"%s","operation":"AUTHORIZE",
                                 "paymentMethodToken":"pm_card_visa","amountMinor":1000,"currency":"USD"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVE"))
                .andExpect(jsonPath("$.source").value("TEST_CARD"));
    }

    @Test
    void anAuthorizeOnlyOverrideNeverAppliesToRefundAndIsNeverConsumedByIt() throws Exception {
        UUID merchantId = UUID.randomUUID();
        overrideService.create(merchantId, "test", SimulationScenario.FORCE_DECLINE, "insufficient_funds", null,
                null, 1, null);

        // REFUND: no override scenario targets it (§8.2) — falls straight to mode default.
        performDecision(signedPost(merchantId, "test")
                        .content("""
                                {"decisionKey":"%s","paymentId":"%s","operation":"REFUND",
                                 "amountMinor":1000,"currency":"USD"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVE"))
                .andExpect(jsonPath("$.source").value("MODE_DEFAULT"));

        // The override is still untouched — an AUTHORIZE right after still sees it.
        performDecision(signedPost(merchantId, "test")
                        .content("""
                                {"decisionKey":"%s","paymentId":"%s","operation":"AUTHORIZE",
                                 "amountMinor":1000,"currency":"USD"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DECLINE"))
                .andExpect(jsonPath("$.source").value("OVERRIDE"));
    }

    @Test
    void aWebhookScenarioOverrideNeverReachesTheEngine() throws Exception {
        UUID merchantId = UUID.randomUUID();
        overrideService.create(merchantId, "test", SimulationScenario.DUPLICATE_WEBHOOKS, null, null, null, 5, null);

        performDecision(signedPost(merchantId, "test")
                        .content("""
                                {"decisionKey":"%s","paymentId":"%s","operation":"AUTHORIZE",
                                 "amountMinor":1000,"currency":"USD"}
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVE"))
                .andExpect(jsonPath("$.source").value("MODE_DEFAULT"));
    }

    @Test
    void authorizingWithADelayedSettlementCardSchedulesADeferredCaptureWithoutAffectingTheAuthorizeOutcome()
            throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant before = Instant.now();

        performDecision(signedPost(merchantId, "test")
                        .content("""
                                {"decisionKey":"%s","paymentId":"%s","operation":"AUTHORIZE",
                                 "paymentMethodToken":"pm_card_delayedSettlement","amountMinor":1000,"currency":"USD"}
                                """.formatted(UUID.randomUUID(), paymentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVE"))
                .andExpect(jsonPath("$.source").value("TEST_CARD"));

        List<ScheduledOutcome> scheduled = scheduledOutcomeRepository.findAll().stream()
                .filter(s -> s.getPaymentId().equals(paymentId))
                .toList();
        assertThat(scheduled).hasSize(1);
        ScheduledOutcome outcome = scheduled.get(0);
        assertThat(outcome.getOperation()).isEqualTo(Operation.CAPTURE);
        assertThat(outcome.getMerchantId()).isEqualTo(merchantId);
        assertThat(outcome.getMode()).isEqualTo("test");
        assertThat(outcome.getFireAt()).isAfterOrEqualTo(before.plusMillis(4900));
        assertThat(outcome.isDelivered()).isFalse();
    }

    @Test
    void anActiveDelaySettlementOverrideSchedulesADeferredCaptureAndIsConsumed() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        overrideService.create(merchantId, "test", SimulationScenario.DELAY_SETTLEMENT, null, null, 3000, 1, null);

        performDecision(signedPost(merchantId, "test")
                        .content("""
                                {"decisionKey":"%s","paymentId":"%s","operation":"AUTHORIZE",
                                 "amountMinor":1000,"currency":"USD"}
                                """.formatted(UUID.randomUUID(), paymentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVE"))
                .andExpect(jsonPath("$.source").value("MODE_DEFAULT")); // DELAY_SETTLEMENT never applies to AUTHORIZE

        List<ScheduledOutcome> scheduled = scheduledOutcomeRepository.findAll().stream()
                .filter(s -> s.getPaymentId().equals(paymentId))
                .toList();
        assertThat(scheduled).hasSize(1);
        assertThat(scheduled.get(0).getOperation()).isEqualTo(Operation.CAPTURE);

        assertThat(overrideService.findActive(merchantId, "test")).isEmpty(); // remainingCount was 1, now exhausted
    }

    @Test
    void authorizingWithAPlainApprovingCardSchedulesNothing() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        performDecision(signedPost(merchantId, "test")
                        .content("""
                                {"decisionKey":"%s","paymentId":"%s","operation":"AUTHORIZE",
                                 "paymentMethodToken":"pm_card_visa","amountMinor":1000,"currency":"USD"}
                                """.formatted(UUID.randomUUID(), paymentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROVE"));

        assertThat(scheduledOutcomeRepository.findAll().stream().filter(s -> s.getPaymentId().equals(paymentId)))
                .isEmpty();
    }

    /** Completes the two-step dispatch every async controller return requires under MockMvc. */
    private ResultActions performDecision(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult mvcResult = mockMvc.perform(request).andExpect(request().asyncStarted()).andReturn();
        return mockMvc.perform(asyncDispatch(mvcResult));
    }

    private static MockHttpServletRequestBuilder signedPost(UUID merchantId, String mode) {
        long issuedAt = Instant.now().getEpochSecond();
        String signature = SIGNER.sign(SECRET, merchantId.toString(), mode, SERVICE_KEY_ID, SERVICE_SCOPES,
                null, null, issuedAt);
        return post("/internal/v1/sandbox/decisions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(InternalContextHeaders.MERCHANT_ID, merchantId.toString())
                .header(InternalContextHeaders.MODE, mode)
                .header(InternalContextHeaders.KEY_ID, SERVICE_KEY_ID)
                .header(InternalContextHeaders.SCOPES, SERVICE_SCOPES)
                .header(InternalContextHeaders.ISSUED_AT, String.valueOf(issuedAt))
                .header(InternalContextHeaders.SIGNATURE, signature);
    }
}
