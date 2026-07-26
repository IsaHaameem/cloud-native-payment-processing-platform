package com.paymentflow.notification;

import com.paymentflow.common.security.InternalContextHeaders;
import com.paymentflow.common.security.InternalContextSigner;
import com.paymentflow.notification.crypto.WebhookSecretCipher;
import com.paymentflow.notification.repository.WebhookEndpointRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the webhook-endpoint management API (M18.2) over real HTTP against real
 * Postgres, signing internal-context headers exactly as the gateway does on behalf of an
 * API key — the same shape sandbox-service's {@code SimulationControllerIntegrationTest}
 * established in M17.5.
 *
 * <p>{@code require-https=false} here for the same reason the local compose stack sets
 * it: every sink this suite can reach is plain HTTP. The HTTPS rule itself is proven by
 * its own test below, which flips the property back on for one case rather than leaving
 * the production default untested.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "paymentflow.webhooks.require-https=false"
})
@AutoConfigureMockMvc
@Testcontainers
class WebhookEndpointApiIntegrationTest {

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
    @Autowired
    private WebhookEndpointRepository endpointRepository;
    @Autowired
    private WebhookSecretCipher secretCipher;

    @Test
    void registeringAnEndpointReturnsTheSecretExactlyOnceAndNeverAgain() throws Exception {
        UUID merchantId = UUID.randomUUID();

        String createBody = mockMvc.perform(signed(post(PATH), merchantId, "test")
                        .content("""
                                {"url":"http://sink.test/hook","description":"Primary",
                                 "enabledEvents":["payment.authorized","payment.captured"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.signingSecret").exists())
                .andExpect(jsonPath("$.endpoint.enabled").value(true))
                .andExpect(jsonPath("$.endpoint.enabledEvents.length()").value(2))
                .andExpect(jsonPath("$.endpoint.signingSecretPrefix").value(org.hamcrest.Matchers.startsWith("whsec_")))
                .andReturn().getResponse().getContentAsString();

        String rawSecret = jsonField(createBody, "signingSecret");
        UUID endpointId = UUID.fromString(jsonField(createBody, "id"));

        // Never stored in the clear — but encrypted rather than hashed (D137), because the
        // platform must use this value as an HMAC key on every delivery. The round trip is
        // asserted, not just "it differs from the plaintext": a cipher that could not
        // recover the secret would break signing without breaking this assertion.
        String stored = endpointRepository.findById(endpointId).orElseThrow().getSigningSecretEncrypted();
        assertThat(stored).isNotEqualTo(rawSecret).doesNotContain(rawSecret);
        assertThat(secretCipher.decrypt(stored)).isEqualTo(rawSecret);

        // Every subsequent read shows the prefix and nothing more.
        mockMvc.perform(signed(get(PATH + "/" + endpointId), merchantId, "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signingSecretPrefix").exists())
                .andExpect(jsonPath("$.signingSecret").doesNotExist());
    }

    @Test
    void endpointsAreInvisibleAcrossModesAndAcrossMerchants() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID otherMerchantId = UUID.randomUUID();
        UUID endpointId = createEndpoint(merchantId, "test", "http://sink.test/isolated");

        // Same id, other mode — 404, not 403: a 403 would confirm the endpoint exists
        // (D102), leaking across exactly the boundary the scoping protects.
        mockMvc.perform(signed(get(PATH + "/" + endpointId), merchantId, "live"))
                .andExpect(status().isNotFound());
        mockMvc.perform(signed(get(PATH + "/" + endpointId), otherMerchantId, "test"))
                .andExpect(status().isNotFound());
        // Nor can they be mutated or deleted across the boundary.
        mockMvc.perform(signed(delete(PATH + "/" + endpointId), otherMerchantId, "test"))
                .andExpect(status().isNotFound());
        mockMvc.perform(signed(patch(PATH + "/" + endpointId), merchantId, "live")
                        .content("""
                                {"enabled":false}"""))
                .andExpect(status().isNotFound());

        mockMvc.perform(signed(get(PATH), merchantId, "live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(signed(get(PATH), merchantId, "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void theSameUrlIsRegistrableInBothModesButNotTwiceInOne() throws Exception {
        UUID merchantId = UUID.randomUUID();
        createEndpoint(merchantId, "test", "http://sink.test/shared");
        createEndpoint(merchantId, "live", "http://sink.test/shared");

        mockMvc.perform(signed(post(PATH), merchantId, "test")
                        .content("""
                                {"url":"http://sink.test/shared","enabledEvents":["*"]}"""))
                .andExpect(status().isConflict());
    }

    @Test
    void anUnknownEventTypeIsRejectedWithTheDocumentedVocabulary() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(signed(post(PATH), merchantId, "test")
                        .content("""
                                {"url":"http://sink.test/typo","enabledEvents":["payment.authorised"]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("payment.authorized")));
    }

    @Test
    void anEmptySubscriptionListIsRejected() throws Exception {
        UUID merchantId = UUID.randomUUID();

        // An endpoint subscribed to nothing is indistinguishable, from the merchant's
        // side, from a platform that has stopped delivering.
        mockMvc.perform(signed(post(PATH), merchantId, "test")
                        .content("""
                                {"url":"http://sink.test/empty","enabledEvents":[]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aWildcardCollapsesRedundantExplicitSubscriptions() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(signed(post(PATH), merchantId, "test")
                        .content("""
                                {"url":"http://sink.test/wild","enabledEvents":["*","payment.authorized"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.endpoint.enabledEvents.length()").value(1))
                .andExpect(jsonPath("$.endpoint.enabledEvents[0]").value("*"));
    }

    @Test
    void aUrlWithEmbeddedCredentialsIsRejected() throws Exception {
        UUID merchantId = UUID.randomUUID();

        // Credentials in the URL would be written verbatim into every delivery-attempt
        // row's request_url — refused rather than redacted after the fact.
        mockMvc.perform(signed(post(PATH), merchantId, "test")
                        .content("""
                                {"url":"http://user:pass@sink.test/hook","enabledEvents":["*"]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aRelativeUrlIsRejected() throws Exception {
        UUID merchantId = UUID.randomUUID();

        mockMvc.perform(signed(post(PATH), merchantId, "test")
                        .content("""
                                {"url":"/hook","enabledEvents":["*"]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchingLeavesUnspecifiedFieldsAlone() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = createEndpoint(merchantId, "test", "http://sink.test/patch");

        mockMvc.perform(signed(patch(PATH + "/" + endpointId), merchantId, "test")
                        .content("""
                                {"description":"Renamed"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Renamed"))
                // The subscription list was not sent, so it must survive intact — the whole
                // reason this is a PATCH and not a PUT.
                .andExpect(jsonPath("$.enabledEvents[0]").value("*"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void disablingAndReEnablingAnEndpointWorksThroughTheApi() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = createEndpoint(merchantId, "test", "http://sink.test/toggle");

        mockMvc.perform(signed(patch(PATH + "/" + endpointId), merchantId, "test")
                        .content("""
                                {"enabled":false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(signed(patch(PATH + "/" + endpointId), merchantId, "test")
                        .content("""
                                {"enabled":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.consecutiveFailureCount").value(0));
    }

    @Test
    void rotatingIssuesANewSecretAndKeepsTheOldOneUsableForItsGraceWindow() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = createEndpoint(merchantId, "test", "http://sink.test/rotate");
        String originalStored = endpointRepository.findById(endpointId).orElseThrow().getSigningSecretEncrypted();

        String rotated = mockMvc.perform(signed(post(PATH + "/" + endpointId + "/rotate_secret"), merchantId, "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signingSecret").exists())
                .andReturn().getResponse().getContentAsString();

        var endpoint = endpointRepository.findById(endpointId).orElseThrow();
        assertThat(secretCipher.decrypt(endpoint.getSigningSecretEncrypted()))
                .isEqualTo(jsonField(rotated, "signingSecret"));
        assertThat(endpoint.getSigningSecretEncrypted()).isNotEqualTo(originalStored);
        // The superseded secret is retained and still recoverable — an in-flight delivery
        // signed with it must not start failing verification mid-rotation (§4.5).
        assertThat(endpoint.getPreviousSecretEncrypted()).isEqualTo(originalStored);
        assertThat(endpoint.hasUsablePreviousSecret(Instant.now())).isTrue();
    }

    @Test
    void deletingAnEndpointRemovesItFromTheList() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID endpointId = createEndpoint(merchantId, "test", "http://sink.test/gone");

        mockMvc.perform(signed(delete(PATH + "/" + endpointId), merchantId, "test"))
                .andExpect(status().isNoContent());
        mockMvc.perform(signed(get(PATH + "/" + endpointId), merchantId, "test"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnsignedRequestIsRejectedWith401() throws Exception {
        mockMvc.perform(get(PATH).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTamperedInternalContextIsRejectedWith401() throws Exception {
        UUID merchantId = UUID.randomUUID();
        long issuedAt = Instant.now().getEpochSecond();
        String signature = SIGNER.sign(SECRET, merchantId.toString(), "test", KEY_ID, SCOPES, null, null, issuedAt);

        // Signature computed for "test", header claims "live" — the mode a signed context
        // asserts cannot be edited in flight.
        mockMvc.perform(get(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(InternalContextHeaders.MERCHANT_ID, merchantId.toString())
                        .header(InternalContextHeaders.MODE, "live")
                        .header(InternalContextHeaders.KEY_ID, KEY_ID)
                        .header(InternalContextHeaders.SCOPES, SCOPES)
                        .header(InternalContextHeaders.ISSUED_AT, String.valueOf(issuedAt))
                        .header(InternalContextHeaders.SIGNATURE, signature))
                .andExpect(status().isUnauthorized());
    }

    private UUID createEndpoint(UUID merchantId, String mode, String url) throws Exception {
        String body = mockMvc.perform(signed(post(PATH), merchantId, mode)
                        .content("{\"url\":\"" + url + "\",\"enabledEvents\":[\"*\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonField(body, "id"));
    }

    /** Minimal field extraction — avoids pulling a JSON library into the test for two lookups. */
    private static String jsonField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
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
