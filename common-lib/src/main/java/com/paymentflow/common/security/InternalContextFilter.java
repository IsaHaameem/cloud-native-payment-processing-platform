package com.paymentflow.common.security;

import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.common.dto.error.ApiError;
import com.paymentflow.common.error.CommonErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Verifies the HMAC-signed internal merchant context the gateway asserts for an
 * API-key-authenticated request (M15, D100) and, when present and valid, populates
 * both {@link MerchantContextHolder} and {@link SecurityContextHolder} so every
 * downstream check — business logic and {@code anyRequest().authenticated()} alike —
 * sees an authenticated request without needing a JWT.
 *
 * <p>Fail-closed by construction: a request carrying no {@code X-PF-Internal-*}
 * headers at all is untouched (the existing JWT path decides its fate, exactly as
 * before M15 — this filter is purely additive); a request carrying a present but
 * unsigned, tampered, incomplete, or stale context is rejected with 401 here, before
 * Spring Security's own chain ever runs, and never falls through to "just try the JWT
 * path instead."
 *
 * <p>Registered well before Spring Security's filter chain (see
 * {@code InternalContextAutoConfiguration}), so a security failure here can't reuse
 * each service's own {@code SecurityErrorWriter}/{@code GlobalExceptionHandler} (both
 * only see requests that already reached the DispatcherServlet) — the {@link ApiError}
 * envelope is written directly, mirroring the same contract those produce.
 */
public class InternalContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalContextFilter.class);

    private final InternalContextProperties properties;
    private final InternalContextSigner signer;
    private final ObjectMapper objectMapper;

    public InternalContextFilter(InternalContextProperties properties, InternalContextSigner signer,
                                 ObjectMapper objectMapper) {
        this.properties = properties;
        this.signer = signer;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String merchantIdHeader = request.getHeader(InternalContextHeaders.MERCHANT_ID);
        if (!StringUtils.hasText(merchantIdHeader)) {
            // No internal context on this request at all — leave it untouched for the
            // existing JWT filter chain to authenticate, exactly as before M15.
            filterChain.doFilter(request, response);
            return;
        }

        MerchantContext context = verify(request, merchantIdHeader);
        if (context == null) {
            writeUnauthorized(request, response);
            return;
        }

        try {
            MerchantContextHolder.set(context);
            SecurityContextHolder.getContext().setAuthentication(new MerchantContextAuthenticationToken(context));
            filterChain.doFilter(request, response);
        } finally {
            MerchantContextHolder.clear();
        }
    }

    /** Returns the verified context, or {@code null} if anything about it fails to check out. */
    private MerchantContext verify(HttpServletRequest request, String merchantIdHeader) {
        String mode = request.getHeader(InternalContextHeaders.MODE);
        String keyIdHeader = request.getHeader(InternalContextHeaders.KEY_ID);
        String scopesCsv = request.getHeader(InternalContextHeaders.SCOPES);
        String contactEmail = request.getHeader(InternalContextHeaders.CONTACT_EMAIL);
        String webhookUrl = request.getHeader(InternalContextHeaders.WEBHOOK_URL);
        String issuedAtHeader = request.getHeader(InternalContextHeaders.ISSUED_AT);
        String signature = request.getHeader(InternalContextHeaders.SIGNATURE);

        if (!StringUtils.hasText(mode) || !StringUtils.hasText(keyIdHeader) || !StringUtils.hasText(scopesCsv)
                || !StringUtils.hasText(issuedAtHeader) || !StringUtils.hasText(signature)) {
            log.warn("Internal context header present but incomplete; rejecting.");
            return null;
        }

        long issuedAtEpochSecond;
        UUID merchantId;
        UUID keyId;
        try {
            issuedAtEpochSecond = Long.parseLong(issuedAtHeader);
            merchantId = UUID.fromString(merchantIdHeader);
            keyId = UUID.fromString(keyIdHeader);
        } catch (IllegalArgumentException e) {
            log.warn("Internal context header malformed; rejecting.");
            return null;
        }

        long skewSeconds = Math.abs(Instant.now().getEpochSecond() - issuedAtEpochSecond);
        if (skewSeconds > properties.maxClockSkewSeconds()) {
            log.warn("Internal context signature stale ({}s skew); rejecting.", skewSeconds);
            return null;
        }

        boolean valid = signer.matches(properties.secret(), merchantIdHeader, mode, keyIdHeader, scopesCsv,
                contactEmail, webhookUrl, issuedAtEpochSecond, signature);
        if (!valid) {
            log.warn("Internal context signature invalid; rejecting.");
            return null;
        }

        Set<String> scopes = Set.of(scopesCsv.split(InternalContextHeaders.SCOPES_DELIMITER));
        return new MerchantContext(merchantId, mode, keyId, scopes, contactEmail, webhookUrl);
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiError body = ApiError.of(
                CommonErrorCode.UNAUTHORIZED.httpStatus(),
                CommonErrorCode.UNAUTHORIZED.code(),
                "The internal merchant context could not be verified.",
                request.getRequestURI(),
                MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY));

        response.setStatus(CommonErrorCode.UNAUTHORIZED.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
