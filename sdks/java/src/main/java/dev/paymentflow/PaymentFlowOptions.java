package dev.paymentflow;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The options a {@link PaymentFlow} client is built from. Every one has a default; the only value
 * with no default is the API key, and even that falls back to {@code PAYMENTFLOW_API_KEY}.
 *
 * <pre>{@code
 * PaymentFlow client = PaymentFlow.builder()
 *     .apiKey(System.getenv("PAYMENTFLOW_API_KEY"))
 *     .baseUrl("https://api.paymentflow.dev")
 *     .build();
 * }</pre>
 *
 * <p>§7.1 fixes these names and defaults across every language, so this is a transcription of an
 * agreed table. Validation happens in {@link PaymentFlow#builder()}'s {@code build()}, so a
 * client built with a negative timeout fails there rather than on the first payment.
 */
public final class PaymentFlowOptions {

    final String apiKey;
    final String baseUrl;
    final String apiVersion;
    final Duration timeout;
    final Integer maxRetries;
    final HttpClient httpClient;

    private PaymentFlowOptions(Builder b) {
        this.apiKey = b.apiKey;
        this.baseUrl = b.baseUrl;
        this.apiVersion = b.apiVersion;
        this.timeout = b.timeout;
        this.maxRetries = b.maxRetries;
        this.httpClient = b.httpClient;
    }

    public static Builder builder() {
        return new Builder();
    }

    // Raw, unvalidated accessors. ClientConfig.resolve applies the defaults and the checks.
    public String apiKey() {
        return apiKey;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String apiVersion() {
        return apiVersion;
    }

    public Duration timeout() {
        return timeout;
    }

    public Integer maxRetries() {
        return maxRetries;
    }

    public HttpClient httpClient() {
        return httpClient;
    }

    /** A mutable builder. Not thread-safe; build one client per key and share it. */
    public static final class Builder {

        private String apiKey;
        private String baseUrl;
        private String apiVersion;
        private Duration timeout;
        private Integer maxRetries;
        private HttpClient httpClient;

        private Builder() {}

        /**
         * Your secret API key, sent as {@code Authorization: Bearer <key>}. Falls back to the
         * {@code PAYMENTFLOW_API_KEY} environment variable. The key alone decides both whose data
         * you see and which mode ({@code test}/{@code live}) you see it in.
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** The host to call. Defaults to {@code https://api.paymentflow.dev}. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * The dated API revision to send as {@code PaymentFlow-Version}. Defaults to the revision
         * this build was written against — the only one its types are known to describe.
         */
        public Builder apiVersion(String apiVersion) {
            this.apiVersion = apiVersion;
            return this;
        }

        /** How long one HTTP attempt may take. Default 30 seconds. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * How many times a retryable failure is retried. Default 3, so a call makes at most four
         * attempts. Zero disables retrying without disabling anything else.
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * The {@link HttpClient} to send with. Injectable for tests and for proxy configuration —
         * the two reasons §7.1 lists. Defaults to one with the client's timeout as its connect
         * timeout.
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public PaymentFlowOptions buildOptions() {
            return new PaymentFlowOptions(this);
        }

        /** Resolves, validates, and returns a client. */
        public PaymentFlow build() {
            return new PaymentFlow(buildOptions());
        }
    }
}
