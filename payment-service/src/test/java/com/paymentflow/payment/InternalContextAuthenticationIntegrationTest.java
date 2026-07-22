package com.paymentflow.payment;

import com.paymentflow.common.security.InternalContextHeaders;
import com.paymentflow.common.security.InternalContextProperties;
import com.paymentflow.common.security.InternalContextSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for the <em>downstream half</em> of the M15 API-key path: a request
 * carrying the gateway's HMAC-signed internal merchant context ({@code X-PF-Internal-*})
 * and <b>no JWT</b> must authenticate through payment-service's real Spring Security
 * chain and create a payment.
 *
 * <p>This guards a defect the milestone originally shipped with. The only M15 gateway
 * integration test stubbed payment-service, so {@code InternalContextFilter}'s actual
 * interaction with Spring Security was never exercised — and it was broken two ways: the
 * filter's auto-configuration was missing from {@code AutoConfiguration.imports} (never
 * registered), and it was registered <em>ahead of</em> {@code FilterChainProxy}, where
 * {@code SecurityContextHolderFilter} wiped the authentication it set. The fix registers
 * the filter <em>inside</em> the chain
 * ({@code addFilterBefore(internalContextFilter, AuthorizationFilter.class)}); this test
 * signs with the same {@link InternalContextSigner} and secret the service verifies with,
 * so a regression on either the wiring or the ordering fails here rather than only in a
 * manual docker-compose run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class InternalContextAuthenticationIntegrationTest {

    private static final String CREATE_BODY = """
            {"amountMinor":2000,"currency":"USD","description":"internal-context auth test"}""";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private InternalContextSigner signer;
    @Autowired
    private InternalContextProperties internalContextProperties;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        // Neither is actually reached on the internal-context path: no JWT is validated
        // (so the JWKS is never fetched), and the Feign merchant lookup is skipped when a
        // MerchantContext is already present. Dummy values keep the context self-contained.
        registry.add("paymentflow.services.identity.jwks-uri", () -> "http://localhost:1/oauth2/jwks");
        registry.add("paymentflow.services.merchant.base-uri", () -> "http://localhost:1");
    }

    @Test
    void aValidSignedInternalContextAuthenticatesAndCreatesAPaymentWithNoJwt() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/payments")
                        .headers(signedContext(merchantId, "test", "payments:write"))
                        .header("Idempotency-Key", "internal-ctx-ok-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void aTamperedInternalContextSignatureIsRejected() throws Exception {
        UUID merchantId = UUID.randomUUID();
        HttpHeaders headers = signedContext(merchantId, "test", "payments:write");
        headers.set(InternalContextHeaders.SIGNATURE, "0".repeat(64)); // well-formed hex, wrong signature

        mockMvc.perform(post("/api/v1/payments")
                        .headers(headers)
                        .header("Idempotency-Key", "internal-ctx-tampered-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRequestWithNeitherInternalContextNorJwtIsRejectedFailClosed() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "internal-ctx-none-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isUnauthorized());
    }

    /** Builds a complete, correctly-signed internal-context header set (no contact email / webhook URL). */
    private HttpHeaders signedContext(UUID merchantId, String mode, String scopesCsv) {
        String keyId = UUID.randomUUID().toString();
        long issuedAt = Instant.now().getEpochSecond();
        String signature = signer.sign(internalContextProperties.secret(), merchantId.toString(), mode, keyId,
                scopesCsv, null, null, issuedAt);

        HttpHeaders headers = new HttpHeaders();
        headers.set(InternalContextHeaders.MERCHANT_ID, merchantId.toString());
        headers.set(InternalContextHeaders.MODE, mode);
        headers.set(InternalContextHeaders.KEY_ID, keyId);
        headers.set(InternalContextHeaders.SCOPES, scopesCsv);
        headers.set(InternalContextHeaders.ISSUED_AT, Long.toString(issuedAt));
        headers.set(InternalContextHeaders.SIGNATURE, signature);
        return headers;
    }
}
