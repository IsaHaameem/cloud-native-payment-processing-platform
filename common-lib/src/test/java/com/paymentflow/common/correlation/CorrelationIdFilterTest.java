package com.paymentflow.common.correlation;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesIncomingCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationConstants.CORRELATION_ID_HEADER, "corr-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] duringChain = new String[1];
        FilterChain chain = (req, res) ->
                duringChain[0] = MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY);

        filter.doFilter(request, response, chain);

        assertThat(duringChain[0]).isEqualTo("corr-123");
        assertThat(response.getHeader(CorrelationConstants.CORRELATION_ID_HEADER)).isEqualTo("corr-123");
        // MDC is cleaned up after the request completes.
        assertThat(MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY)).isNull();
    }

    @Test
    void generatesCorrelationIdWhenHeaderAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] duringChain = new String[1];
        FilterChain chain = (req, res) -> {
            duringChain[0] = MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY);
            // requestId is also present within the request scope.
            assertThat(MDC.get(CorrelationConstants.REQUEST_ID_MDC_KEY)).isNotBlank();
        };

        filter.doFilter(request, response, chain);

        assertThat(duringChain[0]).isNotBlank();
        assertThat(response.getHeader(CorrelationConstants.CORRELATION_ID_HEADER)).isEqualTo(duringChain[0]);
        assertThat(MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY)).isNull();
    }
}
