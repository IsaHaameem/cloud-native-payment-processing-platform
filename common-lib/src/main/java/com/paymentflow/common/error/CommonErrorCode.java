package com.paymentflow.common.error;

/**
 * Generic, cross-cutting error codes reused by every service. Domain-specific codes
 * belong to the owning service, not here.
 */
public enum CommonErrorCode implements ErrorCode {

    VALIDATION_FAILED(400, "One or more fields are invalid."),
    BAD_REQUEST(400, "The request could not be understood."),
    UNAUTHORIZED(401, "Authentication is required or has failed."),
    FORBIDDEN(403, "You do not have permission to perform this action."),
    NOT_FOUND(404, "The requested resource was not found."),
    CONFLICT(409, "The request conflicts with the current state of the resource."),
    RATE_LIMITED(429, "Too many requests. Please retry later."),
    INTERNAL_ERROR(500, "An unexpected error occurred."),
    SERVICE_UNAVAILABLE(503, "The service is temporarily unavailable.");

    private final int httpStatus;
    private final String defaultMessage;

    CommonErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }
}
