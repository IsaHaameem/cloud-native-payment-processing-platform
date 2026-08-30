package dev.paymentflow;

import java.time.Duration;

/**
 * Per-call overrides every resource method accepts as an optional last argument. Build one with
 * {@link #builder()}, or pass {@link #NONE} (or {@code null}) to vary nothing.
 */
public final class RequestOptions {

    /** The shared empty instance — most calls vary nothing. */
    public static final RequestOptions NONE = new RequestOptions(new Builder());

    final String idempotencyKey;
    final String correlationId;
    final Duration timeout;
    final Integer maxRetries;

    private RequestOptions(Builder b) {
        this.idempotencyKey = b.idempotencyKey;
        this.correlationId = b.correlationId;
        this.timeout = b.timeout;
        this.maxRetries = b.maxRetries;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String correlationId() {
        return correlationId;
    }

    /** The per-call timeout override, or {@code null} to use the client's. */
    public java.time.Duration timeout() {
        return timeout;
    }

    /** The per-call retry-budget override, or {@code null} to use the client's. */
    public Integer maxRetries() {
        return maxRetries;
    }

    public static final class Builder {

        private String idempotencyKey;
        private String correlationId;
        private Duration timeout;
        private Integer maxRetries;

        private Builder() {}

        /**
         * The idempotency key to send, for operations that take one. Supply your own when the
         * retry must survive your <em>process</em> restarting, not just this SDK's loop — a key
         * generated here is lost with the object that held it. Omit it and one is generated per
         * call.
         */
        public Builder idempotencyKey(String key) {
            this.idempotencyKey = key;
            return this;
        }

        /** Your own identifier for this operation, sent as {@code X-Correlation-Id} and echoed back. */
        public Builder correlationId(String id) {
            this.correlationId = id;
            return this;
        }

        /** Overrides the client's timeout for this call only. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** Overrides the client's retry budget for this call only. */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public RequestOptions build() {
            return new RequestOptions(this);
        }
    }
}
