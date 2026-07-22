package com.paymentflow.gateway.security.apikey;

/** The credential looked like an API key but did not verify — unknown, revoked, or expired. */
public class InvalidApiKeyException extends RuntimeException {
}
