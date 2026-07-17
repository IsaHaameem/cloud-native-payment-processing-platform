package com.paymentflow.identity.security;

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
 * filter chain (before the DispatcherServlet), so 401/403 responses match the same
 * contract that {@code GlobalExceptionHandler} produces for controller exceptions.
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
