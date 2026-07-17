package com.paymentflow.common.dto.error;

/**
 * A single field-level validation failure.
 *
 * <p>Intentionally omits the rejected value: echoing it back could leak sensitive
 * input (passwords, card numbers) into logs and client responses.
 */
public record ApiFieldError(String field, String message) {
}
