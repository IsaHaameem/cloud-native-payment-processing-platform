package com.paymentflow.sandbox;

import com.paymentflow.common.security.InternalContextHeaders;
import com.paymentflow.common.security.InternalContextSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the public control API (§8.2, M17.5) over real HTTP against real Postgres,
 * signing internal-context headers the same way the gateway will on behalf of an
 * {@code sk_test_} key — this controller's methods are plain (non-async) MVC returns,
 * so unlike {@link SandboxDecisionIntegrationTest} no two-step async dispatch is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SimulationControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    private static final String SECRET = "dev-only-insecure-shared-secret-change-me";
    private static final String SERVICE_KEY_ID = UUID.randomUUID().toString();
    private static final String SERVICE_SCOPES = "payments:write";
    private static final InternalContextSigner SIGNER = new InternalContextSigner();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void creatingAnOverrideReturnsItAndItIsThenTheActiveOne() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(signedPost(merchantId, "test")
                        .content("""
                                {"scenario":"FORCE_DECLINE","declineCode":"card_declined","remainingCount":3}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scenario").value("FORCE_DECLINE"))
                .andExpect(jsonPath("$.declineCode").value("card_declined"))
                .andExpect(jsonPath("$.remainingCount").value(3))
                .andExpect(jsonPath("$.enactedFrom").doesNotExist());

        mockMvc.perform(signedGet(merchantId, "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").value("FORCE_DECLINE"));
    }

    @Test
    void liveModeCannotSetAnOverride() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(signedPost(merchantId, "live")
                        .content("""
                                {"scenario":"FORCE_DECLINE","declineCode":"card_declined","remainingCount":3}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void aScenarioMissingItsRequiredFieldIsRejectedWith400() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(signedPost(merchantId, "test")
                        .content("""
                                {"scenario":"FORCE_DECLINE","remainingCount":3}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void neitherRemainingCountNorDurationIsRejectedWith400() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(signedPost(merchantId, "test")
                        .content("""
                                {"scenario":"FORCE_TIMEOUT"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aWebhookScenarioIsAcceptedAndMarkedEnactedFromM18() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(signedPost(merchantId, "test")
                        .content("""
                                {"scenario":"WEBHOOK_FAILURE","remainingCount":5}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enactedFrom").value("M18"));
    }

    @Test
    void settingANewOverrideSupersedesThePreviousOne() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(signedPost(merchantId, "test")
                        .content("""
                                {"scenario":"FORCE_DECLINE","declineCode":"card_declined","remainingCount":3}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(signedPost(merchantId, "test")
                        .content("""
                                {"scenario":"FORCE_ERROR","errorCode":"processing_error","remainingCount":3}"""))
                .andExpect(status().isCreated());

        mockMvc.perform(signedGet(merchantId, "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").value("FORCE_ERROR"));
    }

    @Test
    void revokingTheActiveOverrideMeansThereIsNoneLeft() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(signedPost(merchantId, "test")
                        .content("""
                                {"scenario":"FORCE_RATE_LIMIT","remainingCount":1}"""))
                .andExpect(status().isCreated());

        mockMvc.perform(signedDelete(merchantId, "test"))
                .andExpect(status().isNoContent());

        mockMvc.perform(signedGet(merchantId, "test"))
                .andExpect(status().isNotFound());
    }

    @Test
    void noActiveOverrideIsNotFound() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(signedGet(merchantId, "test"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingInternalContextIsUnauthorized() throws Exception {
        mockMvc.perform(post("/v1/test/simulations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenario":"FORCE_RATE_LIMIT","remainingCount":1}"""))
                .andExpect(status().isUnauthorized());
    }

    private static MockHttpServletRequestBuilder signedPost(UUID merchantId, String mode) {
        return sign(post("/v1/test/simulations"), merchantId, mode);
    }

    private static MockHttpServletRequestBuilder signedGet(UUID merchantId, String mode) {
        return sign(get("/v1/test/simulations/active"), merchantId, mode);
    }

    private static MockHttpServletRequestBuilder signedDelete(UUID merchantId, String mode) {
        return sign(delete("/v1/test/simulations/active"), merchantId, mode);
    }

    private static MockHttpServletRequestBuilder sign(MockHttpServletRequestBuilder builder, UUID merchantId,
                                                       String mode) {
        long issuedAt = Instant.now().getEpochSecond();
        String signature = SIGNER.sign(SECRET, merchantId.toString(), mode, SERVICE_KEY_ID, SERVICE_SCOPES,
                null, null, issuedAt);
        return builder
                .contentType(MediaType.APPLICATION_JSON)
                .header(InternalContextHeaders.MERCHANT_ID, merchantId.toString())
                .header(InternalContextHeaders.MODE, mode)
                .header(InternalContextHeaders.KEY_ID, SERVICE_KEY_ID)
                .header(InternalContextHeaders.SCOPES, SERVICE_SCOPES)
                .header(InternalContextHeaders.ISSUED_AT, String.valueOf(issuedAt))
                .header(InternalContextHeaders.SIGNATURE, signature);
    }
}
