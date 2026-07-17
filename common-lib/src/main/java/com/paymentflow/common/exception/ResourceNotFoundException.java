package com.paymentflow.common.exception;

import com.paymentflow.common.error.CommonErrorCode;

/** Thrown when a requested resource does not exist. Maps to HTTP 404. */
public class ResourceNotFoundException extends PlatformException {

    public ResourceNotFoundException(String message) {
        super(CommonErrorCode.NOT_FOUND, message);
    }

    /** Convenience factory producing a consistent "{resource} with id '{id}' was not found." message. */
    public static ResourceNotFoundException of(String resource, Object id) {
        return new ResourceNotFoundException("%s with id '%s' was not found.".formatted(resource, id));
    }
}
