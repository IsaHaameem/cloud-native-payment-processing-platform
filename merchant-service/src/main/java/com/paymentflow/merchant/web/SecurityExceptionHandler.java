package com.paymentflow.merchant.web;

import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.common.dto.error.ApiError;
import com.paymentflow.common.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps Spring Security exceptions that surface at the DispatcherServlet — notably
 * method-security {@code @PreAuthorize} denials — to the standard {@link ApiError}
 * envelope. Filter-chain failures are handled separately by the entry point and
 * access-denied handler; this advice covers the exceptions the common-lib handler
 * (which stays security-agnostic) must not swallow as generic 500s.
 */
@RestControllerAdvice
public class SecurityExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(CommonErrorCode.FORBIDDEN, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return build(CommonErrorCode.UNAUTHORIZED, request);
    }

    private static ResponseEntity<ApiError> build(CommonErrorCode errorCode, HttpServletRequest request) {
        ApiError body = ApiError.of(
                errorCode.httpStatus(),
                errorCode.code(),
                errorCode.defaultMessage(),
                request.getRequestURI(),
                MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY));
        return ResponseEntity.status(HttpStatus.valueOf(errorCode.httpStatus())).body(body);
    }
}
