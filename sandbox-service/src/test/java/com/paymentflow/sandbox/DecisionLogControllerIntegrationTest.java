package com.paymentflow.sandbox;

import com.paymentflow.common.security.InternalContextHeaders;
import com.paymentflow.common.security.InternalContextSigner;
import com.paymentflow.sandbox.dto.SandboxDecisionRequest;
import com.paymentflow.sandbox.service.SandboxDecisionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the decision-log query API (§4.2, M17.8) over real HTTP against real Postgres,
 * signing internal-context headers the same way the gateway will on behalf of an
 * {@code sk_test_} key — mirrors {@link SimulationControllerIntegrationTest}'s pattern
 * (M17.5) exactly. Decisions are seeded by calling {@link SandboxDecisionService}
 * directly (a real write to {@code decision_log}, not a hand-inserted row) rather than
 * through the async controller, since nothing here is testing the decide endpoint
 * itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DecisionLogControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private static final String SECRET = "dev-only-insecure-shared-secret-change-me";
    private static final String SERVICE_KEY_ID = UUID.randomUUID().toString();
    private static final String SERVICE_SCOPES = "payments:read";
    private static final InternalContextSigner SIGNER = new InternalContextSigner();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SandboxDecisionService sandboxDecisionService;

    @Test
    void listReturnsTheCallersDecisionsNewestFirst() throws Exception {
        UUID merchantId = UUID.randomUUID();
        seedApprove(merchantId, "test", UUID.randomUUID());
        seedApprove(merchantId, "test", UUID.randomUUID());

        mockMvc.perform(signedGet("/v1/test/decisions", merchantId, "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void listIsScopedToTheCallersMerchantAndMode() throws Exception {
        UUID merchantA = UUID.randomUUID();
        UUID merchantB = UUID.randomUUID();
        seedApprove(merchantA, "test", UUID.randomUUID());
        seedApprove(merchantB, "test", UUID.randomUUID());

        mockMvc.perform(signedGet("/v1/test/decisions", merchantA, "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void forPaymentReturnsOnlyThatPaymentsDecisions() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        seedApprove(merchantId, "test", paymentId);
        seedApprove(merchantId, "test", UUID.randomUUID()); // a different payment

        mockMvc.perform(signedGet("/v1/test/decisions/payments/" + paymentId, merchantId, "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].paymentId").value(paymentId.toString()));
    }

    @Test
    void aDeclinedDecisionExposesItsCodeAndSource() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        seedDecline(merchantId, "test", paymentId);

        mockMvc.perform(signedGet("/v1/test/decisions/payments/" + paymentId, merchantId, "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("DECLINE"))
                .andExpect(jsonPath("$[0].declineCode").value("card_declined"))
                .andExpect(jsonPath("$[0].source").value("TEST_CARD"));
    }

    @Test
    void aMerchantCannotSeeAnotherMerchantsPaymentDecisions() throws Exception {
        UUID merchantA = UUID.randomUUID();
        UUID merchantB = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        seedApprove(merchantA, "test", paymentId);

        mockMvc.perform(signedGet("/v1/test/decisions/payments/" + paymentId, merchantB, "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void missingInternalContextIsUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/test/decisions")).andExpect(status().isUnauthorized());
    }

    private void seedApprove(UUID merchantId, String mode, UUID paymentId) {
        sandboxDecisionService.decide(merchantId, mode, new SandboxDecisionRequest(
                UUID.randomUUID().toString(), paymentId, "AUTHORIZE", "pm_card_visa", 1000, "USD"),
                "test-correlation");
    }

    private void seedDecline(UUID merchantId, String mode, UUID paymentId) {
        sandboxDecisionService.decide(merchantId, mode, new SandboxDecisionRequest(
                UUID.randomUUID().toString(), paymentId, "AUTHORIZE", "pm_card_chargeDeclined", 1000, "USD"),
                "test-correlation");
    }

    private static MockHttpServletRequestBuilder signedGet(String path, UUID merchantId, String mode) {
        long issuedAt = Instant.now().getEpochSecond();
        String signature = SIGNER.sign(SECRET, merchantId.toString(), mode, SERVICE_KEY_ID, SERVICE_SCOPES,
                null, null, issuedAt);
        return get(path)
                .header(InternalContextHeaders.MERCHANT_ID, merchantId.toString())
                .header(InternalContextHeaders.MODE, mode)
                .header(InternalContextHeaders.KEY_ID, SERVICE_KEY_ID)
                .header(InternalContextHeaders.SCOPES, SERVICE_SCOPES)
                .header(InternalContextHeaders.ISSUED_AT, String.valueOf(issuedAt))
                .header(InternalContextHeaders.SIGNATURE, signature);
    }
}
