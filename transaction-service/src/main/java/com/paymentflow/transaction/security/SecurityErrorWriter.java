package com.paymentflow.transaction.security;

import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.common.dto.error.ApiError;
import com.paymentflow.common.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Writes the standard {@link ApiError} envelope for security failures raised in the
 * filter chain — before the DispatcherServlet, where {@code GlobalExceptionHandler}
 * could have seen them. Identical to payment-service's, sandbox-service's and
 * notification-service's classes of the same name, so every service in the platform
 * produces the same contract for the same failure.
 */
@Component
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, CommonErrorCode errorCode)
            throws IOException {
        ApiError body = ApiError.of(
                errorCode.httpStatus(),
                errorCode.code(),
                errorCode.defaultMessage(),
                request.getRequestURI(),
                MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY));

        response.setStatus(errorCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
