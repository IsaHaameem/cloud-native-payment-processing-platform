package dev.paymentflow;

/**
 * An {@code Idempotency-Key} problem — most often a concurrent request still holding the same
 * key. Distinct from {@link InvalidRequestException} despite sharing a status, because this one
 * <em>may</em> succeed on a later attempt and the other never will.
 */
public final class IdempotencyException extends PaymentFlowException {

    public IdempotencyException(String message, Detail detail, Throwable cause) {
        super(message, detail, cause);
    }
}
