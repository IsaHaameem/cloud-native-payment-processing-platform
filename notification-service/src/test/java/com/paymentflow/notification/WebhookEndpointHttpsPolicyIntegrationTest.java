package com.paymentflow.notification;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTPS-only rule (§4.5) under its <em>production</em> setting. A separate class
 * rather than a case inside {@link WebhookEndpointApiIntegrationTest} because
 * {@code requireHttps} is read from configuration at registration time, and that suite
 * necessarily runs with it off — every sink it can reach is plain HTTP.
 *
 * <p>Without this class the default would be the one branch of the policy no test ever
 * exercises, which is precisely the shape of gap a milestone is supposed to close rather
 * than create: the relaxation is what is exceptional, so the strict path is what needs
 * proving.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "paymentflow.webhooks.require-https=true"
})
@AutoConfigureMockMvc
@Testcontainers
class WebhookEndpointHttpsPolicyIntegrationTest {

    private static final String PATH = "/v1/webhook_endpoints";
    private static final String SECRET = "dev-only-insecure-shared-secret-change-me";
    private static final String KEY_ID = UUID.randomUUID().toString();
    private static final String SCOPES = "webhooks:manage";
    private static final InternalContextSigner SIGNER = new InternalContextSigner();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aPlainHttpEndpointIsRejected() throws Exception {
        mockMvc.perform(signed(post(PATH), UUID.randomUUID())
                        .content("""
                                {"url":"http://sink.test/insecure","enabledEvents":["*"]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anHttpsEndpointIsAccepted() throws Exception {
        mockMvc.perform(signed(post(PATH), UUID.randomUUID())
                        .content("""
                                {"url":"https://sink.test/secure","enabledEvents":["*"]}"""))
                .andExpect(status().isCreated());
    }

    @Test
    void anUppercaseSchemeIsStillRecognisedAsHttps() throws Exception {
        // Scheme comparison is case-insensitive per RFC 3986; a case-sensitive check
        // would reject a legitimate URL for a reason no merchant could guess.
        mockMvc.perform(signed(post(PATH), UUID.randomUUID())
                        .content("""
                                {"url":"HTTPS://sink.test/uppercase","enabledEvents":["*"]}"""))
                .andExpect(status().isCreated());
    }

    @Test
    void aNonHttpSchemeIsRejected() throws Exception {
        mockMvc.perform(signed(post(PATH), UUID.randomUUID())
                        .content("""
                                {"url":"file:///etc/passwd","enabledEvents":["*"]}"""))
                .andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder builder, UUID merchantId) {
        long issuedAt = Instant.now().getEpochSecond();
        String signature = SIGNER.sign(SECRET, merchantId.toString(), "test", KEY_ID, SCOPES, null, null, issuedAt);
        return builder
                .contentType(MediaType.APPLICATION_JSON)
                .header(InternalContextHeaders.MERCHANT_ID, merchantId.toString())
                .header(InternalContextHeaders.MODE, "test")
                .header(InternalContextHeaders.KEY_ID, KEY_ID)
                .header(InternalContextHeaders.SCOPES, SCOPES)
                .header(InternalContextHeaders.ISSUED_AT, String.valueOf(issuedAt))
                .header(InternalContextHeaders.SIGNATURE, signature);
    }
}
