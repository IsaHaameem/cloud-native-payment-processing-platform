package dev.paymentflow.model;

/**
 * One field-level validation failure, as carried in an error body's {@code errors} array when
 * more than one field was rejected. The rejected value is deliberately never echoed back.
 */
public record ApiFieldError(String field, String message) {}
