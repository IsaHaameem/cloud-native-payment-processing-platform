package com.paymentflow.common.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InternalContextFilterTest {

    private static final String SECRET = "test-shared-secret";
    private final InternalContextSigner signer = new InternalContextSigner();
    private final InternalContextProperties properties = new InternalContextProperties(SECRET, 30);
    private final InternalContextFilter filter = new InternalContextFilter(properties, signer, new ObjectMapper());

    @AfterEach
    void clearContext() {
        MerchantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void requestWithNoInternalHeadersPassesThroughUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> {
            chainCalled[0] = true;
            assertThat(MerchantContextHolder.get()).isEmpty();
        };

        filter.doFilter(request, response, chain);

        assertThat(chainCalled[0]).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aValidSignedContextAuthenticatesAndPopulatesTheHolder() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        long issuedAt = Instant.now().getEpochSecond();
        String signature = signer.sign(SECRET, merchantId.toString(), "test", keyId.toString(), "payments:write",
                "billing@acme.test", null, issuedAt);

        MockHttpServletRequest request = validRequest(merchantId, keyId, "payments:write", "billing@acme.test",
                null, issuedAt, signature);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> {
            chainCalled[0] = true;
            assertThat(MerchantContextHolder.get()).isPresent();
            assertThat(MerchantContextHolder.get().get().merchantId()).isEqualTo(merchantId);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isInstanceOf(MerchantContextAuthenticationToken.class);
        };

        filter.doFilter(request, response, chain);

        assertThat(chainCalled[0]).isTrue();
        // Cleared after the request completes, mirroring CorrelationIdFilter's MDC discipline.
        assertThat(MerchantContextHolder.get()).isEmpty();
    }

    @Test
    void aTamperedSignatureIsRejectedWith401AndNeverReachesTheChain() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        long issuedAt = Instant.now().getEpochSecond();
        String signature = signer.sign(SECRET, merchantId.toString(), "test", keyId.toString(), "payments:write",
                "billing@acme.test", null, issuedAt);

        // A different merchantId than the one actually signed for.
        MockHttpServletRequest request = validRequest(UUID.randomUUID(), keyId, "payments:write",
                "billing@acme.test", null, issuedAt, signature);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> chainCalled[0] = true;

        filter.doFilter(request, response, chain);

        assertThat(chainCalled[0]).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    }

    @Test
    void aStaleSignatureIsRejectedWith401() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        long issuedAt = Instant.now().minusSeconds(3600).getEpochSecond(); // way outside the 30s skew window
        String signature = signer.sign(SECRET, merchantId.toString(), "test", keyId.toString(), "payments:write",
                "billing@acme.test", null, issuedAt);

        MockHttpServletRequest request = validRequest(merchantId, keyId, "payments:write", "billing@acme.test",
                null, issuedAt, signature);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> chainCalled[0] = true;

        filter.doFilter(request, response, chain);

        assertThat(chainCalled[0]).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void anIncompleteHeaderSetIsRejectedWith401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalContextHeaders.MERCHANT_ID, UUID.randomUUID().toString());
        // Every other required header is missing.
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> chainCalled[0] = true;

        filter.doFilter(request, response, chain);

        assertThat(chainCalled[0]).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    // ---------------------------------------------------------------------------------
    // M23.0 — a second principal, verified by the same filter (D185).
    // ---------------------------------------------------------------------------------

    @Test
    void aValidSignedSessionContextAuthenticatesAndCarriesTheUser() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        long issuedAt = Instant.now().getEpochSecond();
        String signature = signer.sign(SECRET, merchantId.toString(), "live", InternalPrincipal.SESSION,
                userId.toString(), null, "payments:write", "billing@acme.test", null, issuedAt);

        MockHttpServletRequest request = sessionRequest(merchantId, userId, "live", "payments:write",
                "billing@acme.test", issuedAt, signature);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> {
            chainCalled[0] = true;
            MerchantContext context = MerchantContextHolder.get().orElseThrow();
            assertThat(context.principal()).isEqualTo(InternalPrincipal.SESSION);
            assertThat(context.userId()).isEqualTo(userId);
            assertThat(context.keyId()).isNull();
            assertThat(context.merchantId()).isEqualTo(merchantId);
            assertThat(context.mode()).isEqualTo("live");
        };

        filter.doFilter(request, response, chain);

        assertThat(chainCalled[0]).isTrue();
        assertThat(MerchantContextHolder.get()).isEmpty();
    }

    @Test
    void aSessionContextWithoutAUserIsRejectedWith401() throws Exception {
        UUID merchantId = UUID.randomUUID();
        long issuedAt = Instant.now().getEpochSecond();
        String signature = signer.sign(SECRET, merchantId.toString(), "test", InternalPrincipal.SESSION, null, null,
                "payments:read", null, null, issuedAt);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalContextHeaders.MERCHANT_ID, merchantId.toString());
        request.addHeader(InternalContextHeaders.MODE, "test");
        request.addHeader(InternalContextHeaders.PRINCIPAL, InternalPrincipal.SESSION.wireValue());
        request.addHeader(InternalContextHeaders.SCOPES, "payments:read");
        request.addHeader(InternalContextHeaders.ISSUED_AT, Long.toString(issuedAt));
        request.addHeader(InternalContextHeaders.SIGNATURE, signature);

        assertRejected(request);
    }

    @Test
    void aSessionContextCarryingAKeyIdIsRejectedWith401() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        long issuedAt = Instant.now().getEpochSecond();
        // Correctly signed for exactly these headers, so this is refused on the shape rule
        // rather than by the signature check. A session has no key, and a context claiming
        // both is one whose audit trail cannot be trusted.
        String signature = signer.sign(SECRET, merchantId.toString(), "test", InternalPrincipal.SESSION,
                userId.toString(), keyId.toString(), "payments:read", null, null, issuedAt);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalContextHeaders.MERCHANT_ID, merchantId.toString());
        request.addHeader(InternalContextHeaders.MODE, "test");
        request.addHeader(InternalContextHeaders.PRINCIPAL, InternalPrincipal.SESSION.wireValue());
        request.addHeader(InternalContextHeaders.USER_ID, userId.toString());
        request.addHeader(InternalContextHeaders.KEY_ID, keyId.toString());
        request.addHeader(InternalContextHeaders.SCOPES, "payments:read");
        request.addHeader(InternalContextHeaders.ISSUED_AT, Long.toString(issuedAt));
        request.addHeader(InternalContextHeaders.SIGNATURE, signature);

        assertRejected(request);
    }

    @Test
    void anApiKeyContextCarryingAUserIdIsRejectedWith401() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        long issuedAt = Instant.now().getEpochSecond();
        String signature = signer.sign(SECRET, merchantId.toString(), "test", InternalPrincipal.API_KEY,
                userId.toString(), keyId.toString(), "payments:read", null, null, issuedAt);

        MockHttpServletRequest request = validRequest(merchantId, keyId, "payments:read", null, null, issuedAt,
                signature);
        request.addHeader(InternalContextHeaders.USER_ID, userId.toString());

        assertRejected(request);
    }

    @Test
    void anUnrecognisedPrincipalIsRejectedRatherThanDefaulted() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        long issuedAt = Instant.now().getEpochSecond();
        String signature = signer.sign(SECRET, merchantId.toString(), "test", keyId.toString(), "payments:read",
                null, null, issuedAt);

        MockHttpServletRequest request = validRequest(merchantId, keyId, "payments:read", null, null, issuedAt,
                signature);
        request.addHeader(InternalContextHeaders.PRINCIPAL, "root");

        // A principal this build has no rule for must never be silently treated as the most
        // privileged one it does know.
        assertRejected(request);
    }

    @Test
    void aContextSignedAsASessionButPresentedAsAnApiKeyIsRejectedWith401() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        long issuedAt = Instant.now().getEpochSecond();
        String signature = signer.sign(SECRET, merchantId.toString(), "test", InternalPrincipal.SESSION,
                keyId.toString(), null, "payments:read", null, null, issuedAt);

        // Same values, relabelled: the principal header dropped so it defaults to api_key,
        // and the user id moved into the key-id slot. The signature no longer matches.
        MockHttpServletRequest request = validRequest(merchantId, keyId, "payments:read", null, null, issuedAt,
                signature);

        assertRejected(request);
    }

    @Test
    void aPreM23ContextWithNoPrincipalHeaderStillAuthenticatesAsAnApiKey() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();
        long issuedAt = Instant.now().getEpochSecond();
        // Signed with the M15 overload, exactly as payment-service's sandbox advisor and
        // notification-service's scenario client still do.
        String signature = signer.sign(SECRET, merchantId.toString(), "test", keyId.toString(), "payments:read",
                null, null, issuedAt);

        MockHttpServletRequest request = validRequest(merchantId, keyId, "payments:read", null, null, issuedAt,
                signature);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> {
            chainCalled[0] = true;
            MerchantContext context = MerchantContextHolder.get().orElseThrow();
            assertThat(context.principal()).isEqualTo(InternalPrincipal.API_KEY);
            assertThat(context.keyId()).isEqualTo(keyId);
            assertThat(context.userId()).isNull();
        };

        filter.doFilter(request, response, chain);

        assertThat(chainCalled[0]).isTrue();
    }

    private void assertRejected(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] chainCalled = {false};
        FilterChain chain = (req, res) -> chainCalled[0] = true;

        filter.doFilter(request, response, chain);

        assertThat(chainCalled[0]).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    }

    private static MockHttpServletRequest sessionRequest(UUID merchantId, UUID userId, String mode,
                                                         String scopesCsv, String contactEmail, long issuedAt,
                                                         String signature) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalContextHeaders.MERCHANT_ID, merchantId.toString());
        request.addHeader(InternalContextHeaders.MODE, mode);
        request.addHeader(InternalContextHeaders.PRINCIPAL, InternalPrincipal.SESSION.wireValue());
        request.addHeader(InternalContextHeaders.USER_ID, userId.toString());
        request.addHeader(InternalContextHeaders.SCOPES, scopesCsv);
        if (contactEmail != null) {
            request.addHeader(InternalContextHeaders.CONTACT_EMAIL, contactEmail);
        }
        request.addHeader(InternalContextHeaders.ISSUED_AT, Long.toString(issuedAt));
        request.addHeader(InternalContextHeaders.SIGNATURE, signature);
        return request;
    }

    private static MockHttpServletRequest validRequest(UUID merchantId, UUID keyId, String scopesCsv,
                                                        String contactEmail, String webhookUrl, long issuedAt,
                                                        String signature) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(InternalContextHeaders.MERCHANT_ID, merchantId.toString());
        request.addHeader(InternalContextHeaders.MODE, "test");
        request.addHeader(InternalContextHeaders.KEY_ID, keyId.toString());
        request.addHeader(InternalContextHeaders.SCOPES, scopesCsv);
        if (contactEmail != null) {
            request.addHeader(InternalContextHeaders.CONTACT_EMAIL, contactEmail);
        }
        if (webhookUrl != null) {
            request.addHeader(InternalContextHeaders.WEBHOOK_URL, webhookUrl);
        }
        request.addHeader(InternalContextHeaders.ISSUED_AT, Long.toString(issuedAt));
        request.addHeader(InternalContextHeaders.SIGNATURE, signature);
        return request;
    }
}
