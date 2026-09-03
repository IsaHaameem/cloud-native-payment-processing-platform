package com.paymentflow.agentic.web;

import com.paymentflow.agentic.provider.PaymentProvider;
import com.paymentflow.agentic.provider.ProviderDecision;
import com.paymentflow.common.security.InternalContextFilter;
import com.paymentflow.common.security.InternalContextHeaders;
import com.paymentflow.common.security.InternalContextProperties;
import com.paymentflow.common.security.InternalContextSigner;
import com.paymentflow.common.security.MerchantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The provider-decision endpoint's authentication, exercised through the real
 * {@link InternalContextFilter}.
 *
 * <p>This is the most important test in Phase 13, because the endpoint is the one inbound path
 * into this service from the payment platform. A forged signature has to be rejected
 * <em>before</em> the controller runs — not by the controller noticing something is off, but by
 * the filter refusing on its own authority. So the filter is wired into the chain here rather
 * than the controller being called directly: calling it directly would assert nothing about the
 * boundary, and the boundary is the whole point.
 *
 * <p>Assembled standalone rather than with {@code @SpringBootTest}: the endpoint's security does
 * not depend on a datasource, and requiring Docker to assert that a bad signature is rejected
 * would make the most safety-relevant test in the module the flakiest one in it.
 */
class ProviderDecisionControllerTest {

    private static final String SECRET = "test-only-internal-context-secret";
    private static final UUID MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PAYMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    /** A UUID, because {@code InternalContextFilter} parses this header as one and 401s if it cannot. */
    private static final String KEY_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String SCOPES = "payments:write";

    private static final String BODY = """
            {"decisionKey":"%s:AUTHORIZE","paymentId":"%s","operation":"AUTHORIZE",
             "paymentMethodToken":"rzp_token","amountMinor":250000,"currency":"INR"}"""
            .formatted(PAYMENT_ID, PAYMENT_ID);

    private final InternalContextSigner signer = new InternalContextSigner();
    private final PaymentProvider provider = mock(PaymentProvider.class);
    private final com.paymentflow.agentic.provider.ProviderDecisionRepository decisionRepository =
            mock(com.paymentflow.agentic.provider.ProviderDecisionRepository.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InternalContextFilter filter = new InternalContextFilter(
                new InternalContextProperties(SECRET, 30), signer, new ObjectMapper());
        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new ProviderDecisionController(provider,
                        new com.paymentflow.agentic.observability.AgentMetrics(
                                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                        decisionRepository))
                .addFilters(filter)
                .build();

        when(provider.authorize(any())).thenReturn(ProviderDecision.declined(
                "razorpay_payment_not_collected", ProviderDecision.SOURCE_ORDER_ACCEPTED, "order_1"));
    }

    @AfterEach
    void tearDown() {
        // The context is thread-local and this test drives requests on the calling thread; a
        // leaked context would silently authenticate the next test.
        MerchantContextHolder.clear();
    }

    private MockHttpServletRequestBuilder request(long issuedAt, String signature) {
        return post("/internal/v1/providers/external/decisions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(InternalContextHeaders.MERCHANT_ID, MERCHANT_ID.toString())
                .header(InternalContextHeaders.MODE, "test")
                .header(InternalContextHeaders.KEY_ID, KEY_ID)
                .header(InternalContextHeaders.SCOPES, SCOPES)
                .header(InternalContextHeaders.ISSUED_AT, Long.toString(issuedAt))
                .header(InternalContextHeaders.SIGNATURE, signature)
                .content(BODY);
    }

    private String validSignature(long issuedAt) {
        return signer.sign(SECRET, MERCHANT_ID.toString(), "test", KEY_ID, SCOPES, null, null, issuedAt);
    }

    @Nested
    @DisplayName("a correctly signed context")
    class Accepted {

        @Test
        @DisplayName("is accepted, and the decision reaches the caller")
        void validSignatureIsAccepted() throws Exception {
            long issuedAt = Instant.now().getEpochSecond();

            mockMvc.perform(request(issuedAt, validSignature(issuedAt)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("DECLINE"))
                    .andExpect(jsonPath("$.declineCode").value("razorpay_payment_not_collected"))
                    .andExpect(jsonPath("$.source").value("order_accepted"))
                    .andExpect(jsonPath("$.demo").value(false));

            verify(provider).authorize(any());
        }

        @Test
        @DisplayName("carries a demo approval with its label intact, so a caller cannot mistake one")
        void demoApprovalIsLabelledOnTheWire() throws Exception {
            when(provider.authorize(any())).thenReturn(ProviderDecision.demoApproved("order_2"));
            long issuedAt = Instant.now().getEpochSecond();

            mockMvc.perform(request(issuedAt, validSignature(issuedAt)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("APPROVE"))
                    .andExpect(jsonPath("$.source").value("order_accepted"))
                    .andExpect(jsonPath("$.demo").value(true));
        }

        @Test
        @DisplayName("persists the decision (G-6), provider-neutral, keyed on the decision key")
        void persistsTheDecision() throws Exception {
            when(provider.providerName()).thenReturn("razorpay");
            when(decisionRepository.existsByDecisionKey(anyString())).thenReturn(false);
            long issuedAt = Instant.now().getEpochSecond();

            mockMvc.perform(request(issuedAt, validSignature(issuedAt))).andExpect(status().isOk());

            var captor = org.mockito.ArgumentCaptor.forClass(
                    com.paymentflow.agentic.provider.ProviderDecisionRecord.class);
            verify(decisionRepository).save(captor.capture());
            var record = captor.getValue();
            org.assertj.core.api.Assertions.assertThat(record.getMerchantId()).isEqualTo(MERCHANT_ID);
            org.assertj.core.api.Assertions.assertThat(record.getMode()).isEqualTo("test");
            org.assertj.core.api.Assertions.assertThat(record.getPaymentId()).isEqualTo(PAYMENT_ID);
            org.assertj.core.api.Assertions.assertThat(record.getAmountMinor()).isEqualTo(250000);
            org.assertj.core.api.Assertions.assertThat(record.getCurrency()).isEqualTo("INR");
            org.assertj.core.api.Assertions.assertThat(record.getSource()).isEqualTo("order_accepted");
        }

        @Test
        @DisplayName("is idempotent on the decision key — a retried call inserts nothing")
        void persistIsIdempotent() throws Exception {
            when(decisionRepository.existsByDecisionKey(anyString())).thenReturn(true);
            long issuedAt = Instant.now().getEpochSecond();

            mockMvc.perform(request(issuedAt, validSignature(issuedAt))).andExpect(status().isOk());

            verify(decisionRepository, never()).save(any());
        }

        @Test
        @DisplayName("a persistence failure never fails the decision the caller is waiting for")
        void persistFailureDoesNotFailTheDecision() throws Exception {
            when(decisionRepository.existsByDecisionKey(anyString())).thenReturn(false);
            when(decisionRepository.save(any())).thenThrow(new RuntimeException("db down"));
            long issuedAt = Instant.now().getEpochSecond();

            mockMvc.perform(request(issuedAt, validSignature(issuedAt)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.outcome").value("DECLINE"));
        }

        @Test
        @DisplayName("returns no Razorpay vocabulary — the payment core never learns the provider")
        void responseIsProviderNeutral() throws Exception {
            long issuedAt = Instant.now().getEpochSecond();

            String body = mockMvc.perform(request(issuedAt, validSignature(issuedAt)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // The decline code is the provider's own, which is deliberate — it is the reason a
            // buyer is given. What must not appear is the provider's identity as a field or a
            // structural concept the platform would have to understand.
            org.assertj.core.api.Assertions.assertThat(body)
                    .doesNotContain("razorpay_order")
                    .doesNotContain("orderId")
                    .doesNotContain("keyId");
        }
    }

    @Nested
    @DisplayName("anything else is refused before the controller runs")
    class Rejected {

        @Test
        @DisplayName("a forged signature")
        void forgedSignatureIsRejected() throws Exception {
            long issuedAt = Instant.now().getEpochSecond();

            mockMvc.perform(request(issuedAt, "0".repeat(64))).andExpect(status().isUnauthorized());

            verify(provider, never()).authorize(any());
        }

        @Test
        @DisplayName("a signature that is valid for a different merchant — it does not transfer")
        void signatureOverOtherValuesIsRejected() throws Exception {
            long issuedAt = Instant.now().getEpochSecond();
            String otherMerchant = signer.sign(SECRET, "99999999-9999-9999-9999-999999999999", "test",
                    KEY_ID, SCOPES, null, null, issuedAt);

            mockMvc.perform(request(issuedAt, otherMerchant)).andExpect(status().isUnauthorized());

            verify(provider, never()).authorize(any());
        }

        @Test
        @DisplayName("a signature computed with the wrong secret")
        void wrongSecretIsRejected() throws Exception {
            long issuedAt = Instant.now().getEpochSecond();
            String wrongSecret = signer.sign("a-different-secret", MERCHANT_ID.toString(), "test",
                    KEY_ID, SCOPES, null, null, issuedAt);

            mockMvc.perform(request(issuedAt, wrongSecret)).andExpect(status().isUnauthorized());

            verify(provider, never()).authorize(any());
        }

        @Test
        @DisplayName("a stale context, so a captured request cannot be replayed an hour later")
        void staleContextIsRejected() throws Exception {
            long stale = Instant.now().minusSeconds(3600).getEpochSecond();

            mockMvc.perform(request(stale, validSignature(stale))).andExpect(status().isUnauthorized());

            verify(provider, never()).authorize(any());
        }

        @Test
        @DisplayName("no signed context at all — the controller's own check catches it")
        void unsignedRequestIsRejected() {
            // The filter passes an unsigned request through untouched, exactly as it does for
            // every non-internal route. What stops it here is the controller's second check,
            // which throws UnauthorizedException. In the running service common-lib's
            // GlobalExceptionHandler maps that to 401; this standalone chain has no handler
            // registered, so the exception is what surfaces — and asserting on it is the more
            // direct statement of the property anyway.
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            mockMvc.perform(post("/internal/v1/providers/external/decisions")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(BODY)))
                    .hasRootCauseInstanceOf(com.paymentflow.common.exception.UnauthorizedException.class);

            verify(provider, never()).authorize(any());
        }

        @Test
        @DisplayName("a context missing its signature header entirely")
        void missingSignatureIsRejected() throws Exception {
            long issuedAt = Instant.now().getEpochSecond();

            mockMvc.perform(post("/internal/v1/providers/external/decisions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(InternalContextHeaders.MERCHANT_ID, MERCHANT_ID.toString())
                            .header(InternalContextHeaders.MODE, "test")
                            .header(InternalContextHeaders.KEY_ID, KEY_ID)
                            .header(InternalContextHeaders.SCOPES, SCOPES)
                            .header(InternalContextHeaders.ISSUED_AT, Long.toString(issuedAt))
                            .content(BODY))
                    .andExpect(status().isUnauthorized());

            verify(provider, never()).authorize(any());
        }
    }
}
