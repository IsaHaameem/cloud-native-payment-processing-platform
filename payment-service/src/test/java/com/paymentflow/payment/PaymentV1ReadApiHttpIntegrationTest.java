package com.paymentflow.payment;

import com.paymentflow.common.security.InternalContextHeaders;
import com.paymentflow.common.security.InternalContextProperties;
import com.paymentflow.common.security.InternalContextSigner;
import com.paymentflow.payment.domain.Payment;
import com.paymentflow.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public read surface over real HTTP (M19.8).
 *
 * <p><b>Why this exists as a separate class from {@code PaymentReadApiIntegrationTest}.</b>
 * That test drives the repository and query service directly, on the stated grounds that
 * "what needs proving here is that the SQL is right" — which it does, thoroughly. But it
 * means every M19 query parameter was verified as a *Java argument* and never as a *query
 * string*, and M19.7's gateway tests verify routing against a stub that never binds one.
 * The binding layer between them was the one part of the read surface nothing executed,
 * and the {@code metadata} filter turned out to be broken there (M19.8): a filter that
 * failed <em>open</em>, returning every payment instead of none, which is the failure mode
 * {@code PaymentListFilter} was explicitly written to avoid for {@code status}.
 *
 * <p>So this class asserts the contract the documentation publishes, in the form a client
 * actually sends it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PaymentV1ReadApiHttpIntegrationTest {

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
    private PaymentRepository paymentRepository;
    @Autowired
    private InternalContextSigner signer;
    @Autowired
    private InternalContextProperties internalContextProperties;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        // Neither is reached on the internal-context path — see
        // InternalContextAuthenticationIntegrationTest for the full explanation.
        registry.add("paymentflow.services.identity.jwks-uri", () -> "http://localhost:1/oauth2/jwks");
        registry.add("paymentflow.services.merchant.base-uri", () -> "http://localhost:1");
    }

    @Test
    void theMetadataFilterNarrowsTheListRatherThanBeingIgnored() throws Exception {
        UUID merchantId = UUID.randomUUID();
        save(merchantId, 100, "{\"orderId\":\"A-1\",\"channel\":\"web\"}");
        save(merchantId, 200, "{\"orderId\":\"A-2\"}");
        save(merchantId, 300, null);

        // The published syntax (docs/READ_APIS.md §5). This is the assertion that failed
        // before M19.8: Spring bound nothing for `metadata[orderId]`, the filter went to
        // the query as null, and all three payments came back — a filter failing open on
        // a financial list, which is strictly worse than one that errors.
        mockMvc.perform(get("/v1/payments?metadata[orderId]=A-1").headers(signedContext(merchantId, "test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].amountMinor").value(100))
                .andExpect(jsonPath("$.data[0].metadata.channel").value("web"));

        // Containment, not equality: a partial match still selects.
        mockMvc.perform(get("/v1/payments?metadata[channel]=web").headers(signedContext(merchantId, "test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        // Every named key must match, not any of them.
        mockMvc.perform(get("/v1/payments?metadata[orderId]=A-2&metadata[channel]=web")
                        .headers(signedContext(merchantId, "test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // A value nothing carries selects nothing — never everything.
        mockMvc.perform(get("/v1/payments?metadata[orderId]=A-9").headers(signedContext(merchantId, "test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void theMetadataFilterWorksOnRefundsToo() throws Exception {
        UUID merchantId = UUID.randomUUID();
        save(merchantId, 100, "{\"orderId\":\"R-1\"}");

        // No refunds exist, so the assertion is about the binding, not the data: an
        // ignored filter would return the empty list either way, so the case that matters
        // is that a *malformed* one is refused rather than silently dropped (below).
        mockMvc.perform(get("/v1/refunds?metadata[orderId]=R-1").headers(signedContext(merchantId, "test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void aBareMetadataParameterIsRejectedRatherThanIgnored() throws Exception {
        UUID merchantId = UUID.randomUUID();
        save(merchantId, 100, "{\"orderId\":\"A-1\"}");

        // `?metadata=A-1` is the most likely way to get the syntax wrong. Ignoring it
        // would return the merchant's whole history under the impression it was filtered;
        // the 400 names the correct form instead.
        mockMvc.perform(get("/v1/payments?metadata=A-1").headers(signedContext(merchantId, "test")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/payments?metadata[]=A-1").headers(signedContext(merchantId, "test")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theListEnvelopeAndPaginationAreWhatTheDocumentationPublishes() throws Exception {
        UUID merchantId = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            save(merchantId, 100 + i, null);
        }

        String body = mockMvc.perform(get("/v1/payments?limit=2").headers(signedContext(merchantId, "test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").exists())
                .andExpect(jsonPath("$.data[0].object").value("payment"))
                // Absent, not [], when expand was not asked for.
                .andExpect(jsonPath("$.data[0].refunds").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String cursor = jsonField(body, "nextCursor");
        mockMvc.perform(get("/v1/payments?limit=2&starting_after=" + cursor)
                        .headers(signedContext(merchantId, "test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void theLimitIsClampedAtTheDocumentedCeilingRatherThanRejected() throws Exception {
        UUID merchantId = UUID.randomUUID();
        save(merchantId, 100, null);

        // Documented as clamped: asking for more than the ceiling returns the ceiling.
        mockMvc.perform(get("/v1/payments?limit=1000").headers(signedContext(merchantId, "test")))
                .andExpect(status().isOk());
        // A non-positive limit has no sensible interpretation and is refused.
        mockMvc.perform(get("/v1/payments?limit=0").headers(signedContext(merchantId, "test")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aTypoedStatusIsAFourHundredAndAnUnknownExpandIsIgnored() throws Exception {
        UUID merchantId = UUID.randomUUID();
        save(merchantId, 100, null);

        mockMvc.perform(get("/v1/payments?status=AUTHORISED").headers(signedContext(merchantId, "test")))
                .andExpect(status().isBadRequest());
        // Forward compatibility (§4.10): a relation this version does not have is ignored,
        // not rejected, so an SDK written against a later version still works here.
        mockMvc.perform(get("/v1/payments?expand=disputes").headers(signedContext(merchantId, "test")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].refunds").doesNotExist());
    }

    @Test
    void aTamperedCursorIsRefusedBeforeItReachesTheQuery() throws Exception {
        UUID merchantId = UUID.randomUUID();
        save(merchantId, 100, null);

        mockMvc.perform(get("/v1/payments?starting_after=not-a-cursor").headers(signedContext(merchantId, "test")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherMerchantsPaymentIsNotFoundRatherThanForbidden() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID otherMerchantId = UUID.randomUUID();
        Payment payment = save(merchantId, 100, null);

        // 404 rather than 403 — a 403 would confirm the payment exists (D102).
        mockMvc.perform(get("/v1/payments/" + payment.getId()).headers(signedContext(otherMerchantId, "test")))
                .andExpect(status().isNotFound());
        // And the same id in the other mode is equally not found.
        mockMvc.perform(get("/v1/payments/" + payment.getId()).headers(signedContext(merchantId, "live")))
                .andExpect(status().isNotFound());
    }

    private Payment save(UUID merchantId, long amountMinor, String metadata) {
        return paymentRepository.saveAndFlush(
                Payment.create(merchantId, "test", amountMinor, "USD", "seeded", null, metadata));
    }

    private HttpHeaders signedContext(UUID merchantId, String mode) {
        String keyId = UUID.randomUUID().toString();
        String scopes = "payments:read";
        long issuedAt = Instant.now().getEpochSecond();
        String signature = signer.sign(internalContextProperties.secret(), merchantId.toString(), mode, keyId,
                scopes, null, null, issuedAt);

        HttpHeaders headers = new HttpHeaders();
        headers.set(InternalContextHeaders.MERCHANT_ID, merchantId.toString());
        headers.set(InternalContextHeaders.MODE, mode);
        headers.set(InternalContextHeaders.KEY_ID, keyId);
        headers.set(InternalContextHeaders.SCOPES, scopes);
        headers.set(InternalContextHeaders.ISSUED_AT, Long.toString(issuedAt));
        headers.set(InternalContextHeaders.SIGNATURE, signature);
        return headers;
    }

    /** Minimal field extraction — avoids pulling a JSON library into the test for one lookup. */
    private static String jsonField(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
