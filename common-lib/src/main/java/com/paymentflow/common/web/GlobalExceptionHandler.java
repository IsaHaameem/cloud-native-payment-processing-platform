package com.paymentflow.common.web;

import com.paymentflow.common.correlation.CorrelationConstants;
import com.paymentflow.common.dto.error.ApiError;
import com.paymentflow.common.dto.error.ApiFieldError;
import com.paymentflow.common.error.CommonErrorCode;
import com.paymentflow.common.error.ErrorCode;
import com.paymentflow.common.exception.PlatformException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

/**
 * Translates exceptions into the standard {@link ApiError} envelope with a stable
 * error {@code code} and the active correlation id.
 *
 * <p>Client errors (4xx) are logged at WARN; unexpected errors (5xx) at ERROR with
 * the full stack trace. Internal exception details are never exposed to callers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Any domain/application exception: status and code come from its {@link ErrorCode}. */
    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ApiError> handlePlatform(PlatformException ex, HttpServletRequest request) {
        ErrorCode code = ex.errorCode();
        log.warn("{} [{}] at {}: {}", ex.getClass().getSimpleName(), code.code(),
                request.getRequestURI(), ex.getMessage());
        return build(code.httpStatus(), code.code(), ex.getMessage(), request, List.of());
    }

    /** Bean-validation failures on @Valid request bodies. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiFieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();
        return validationError(request, fieldErrors);
    }

    /** Bean-validation failures on @Validated method parameters / path & query params. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex,
                                                              HttpServletRequest request) {
        List<ApiFieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();
        return validationError(request, fieldErrors);
    }

    /** Malformed body, missing params, or type mismatches. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception ex, HttpServletRequest request) {
        log.warn("Malformed request at {}: {}", request.getRequestURI(), ex.getMessage());
        return build(CommonErrorCode.BAD_REQUEST.httpStatus(), CommonErrorCode.BAD_REQUEST.code(),
                CommonErrorCode.BAD_REQUEST.defaultMessage(), request, List.of());
    }

    /** Catch-all: log fully, respond with a generic message that leaks nothing. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}", request.getRequestURI(), ex);
        return build(CommonErrorCode.INTERNAL_ERROR.httpStatus(), CommonErrorCode.INTERNAL_ERROR.code(),
                CommonErrorCode.INTERNAL_ERROR.defaultMessage(), request, List.of());
    }

    private static ResponseEntity<ApiError> validationError(HttpServletRequest request, List<ApiFieldError> errors) {
        return build(CommonErrorCode.VALIDATION_FAILED.httpStatus(), CommonErrorCode.VALIDATION_FAILED.code(),
                CommonErrorCode.VALIDATION_FAILED.defaultMessage(), request, errors);
    }

    private static ResponseEntity<ApiError> build(int status, String code, String message,
                                                  HttpServletRequest request, List<ApiFieldError> errors) {
        ApiError body = ApiError.of(status, code, message, request.getRequestURI(),
                MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY), errors);
        return ResponseEntity.status(HttpStatus.valueOf(status)).body(body);
    }

    private static ApiFieldError toFieldError(FieldError fieldError) {
        return new ApiFieldError(fieldError.getField(), fieldError.getDefaultMessage());
    }

    private static ApiFieldError toFieldError(ConstraintViolation<?> violation) {
        String field = (violation.getPropertyPath() == null) ? null : lastNode(violation.getPropertyPath().toString());
        return new ApiFieldError(field, violation.getMessage());
    }

    private static String lastNode(String propertyPath) {
        int idx = propertyPath.lastIndexOf('.');
        return (idx >= 0) ? propertyPath.substring(idx + 1) : propertyPath;
    }
}
