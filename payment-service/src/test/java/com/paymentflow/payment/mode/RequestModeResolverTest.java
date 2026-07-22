package com.paymentflow.payment.mode;

import com.paymentflow.common.exception.BadRequestException;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the M16 mode-resolution rule: signed context (API-key path) wins and is
 * never client-overridable; otherwise the {@code X-PF-Mode} header (JWT/dashboard path) is
 * honoured and validated; otherwise the request defaults to test mode.
 */
class RequestModeResolverTest {

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final RequestModeResolver resolver = new RequestModeResolver(request);

    @AfterEach
    void clearContext() {
        MerchantContextHolder.clear();
    }

    @Test
    void apiKeyPathUsesTheSignedContextModeAndIgnoresAnyHeader() {
        MerchantContextHolder.set(new MerchantContext(UUID.randomUUID(), "live", UUID.randomUUID(),
                Set.of("payments:write"), "m@test", null));
        when(request.getHeader(RequestModeResolver.MODE_HEADER)).thenReturn("test"); // must be ignored

        assertThat(resolver.resolve()).isEqualTo("live");
    }

    @Test
    void jwtPathHonoursAValidModeHeader() {
        when(request.getHeader(RequestModeResolver.MODE_HEADER)).thenReturn("live");

        assertThat(resolver.resolve()).isEqualTo("live");
    }

    @Test
    void jwtPathNormalisesTheModeHeaderCaseInsensitively() {
        when(request.getHeader(RequestModeResolver.MODE_HEADER)).thenReturn("LIVE");

        assertThat(resolver.resolve()).isEqualTo("live");
    }

    @Test
    void jwtPathWithoutAModeHeaderDefaultsToTest() {
        when(request.getHeader(RequestModeResolver.MODE_HEADER)).thenReturn(null);

        assertThat(resolver.resolve()).isEqualTo("test");
    }

    @Test
    void anUnrecognisedModeHeaderIsRejected() {
        when(request.getHeader(RequestModeResolver.MODE_HEADER)).thenReturn("staging");

        assertThatThrownBy(resolver::resolve).isInstanceOf(BadRequestException.class);
    }
}
