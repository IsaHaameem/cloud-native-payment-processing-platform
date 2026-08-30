package dev.paymentflow.internal;

import dev.paymentflow.PaymentFlowConfigurationException;
import dev.paymentflow.PaymentFlowOptions;
import dev.paymentflow.model.Contract;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The resolved, validated configuration a client holds. Immutable.
 *
 * <p>Every option is checked here, at construction: a client built with an empty base URL or a
 * key with trailing whitespace should fail on the line that built it, not on the first call.
 * A whitespace-wrapped key is <em>rejected</em>, not trimmed — silently repairing a credential
 * hides that the stored one is wrong, and the symptom (a 401 that looks like a revoked key) is
 * the hardest kind to diagnose.
 */
public final class ClientConfig {

    /** This package's own version. Moves on its own schedule, independent of the API revision. */
    public static final String SDK_VERSION = "0.1.0";

    /** How this SDK identifies itself on every request. Makes adoption measurable in the request log. */
    public static final String USER_AGENT =
            "paymentflow-java/" + SDK_VERSION + " jvm/" + System.getProperty("java.version", "unknown");

    private final String apiKey;
    private final URI baseUri;
    private final String apiVersion;
    private final Duration timeout;
    private final int maxRetries;
    private final HttpClient httpClient;

    private ClientConfig(String apiKey, URI baseUri, String apiVersion, Duration timeout, int maxRetries,
                         HttpClient httpClient) {
        this.apiKey = apiKey;
        this.baseUri = baseUri;
        this.apiVersion = apiVersion;
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.httpClient = httpClient;
    }

    public static ClientConfig resolve(PaymentFlowOptions options) {
        String apiKey = options.apiKey() != null ? options.apiKey() : System.getenv("PAYMENTFLOW_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new PaymentFlowConfigurationException(
                    "No API key. Pass apiKey to PaymentFlow.builder(), or set PAYMENTFLOW_API_KEY.");
        }
        if (!apiKey.trim().equals(apiKey)) {
            throw new PaymentFlowConfigurationException("The API key has leading or trailing whitespace.");
        }

        String rawBase = options.baseUrl() != null ? options.baseUrl() : Contract.DEFAULT_BASE_URL;
        while (rawBase.endsWith("/")) {
            rawBase = rawBase.substring(0, rawBase.length() - 1);
        }
        if (rawBase.isEmpty()) {
            throw new PaymentFlowConfigurationException("baseUrl must not be empty.");
        }
        URI baseUri;
        try {
            baseUri = new URI(rawBase);
        } catch (URISyntaxException e) {
            throw new PaymentFlowConfigurationException("baseUrl is not a valid URL: " + rawBase);
        }
        if (baseUri.getScheme() == null || baseUri.getHost() == null) {
            throw new PaymentFlowConfigurationException("baseUrl must be an absolute URL: " + rawBase);
        }

        String apiVersion = options.apiVersion() != null ? options.apiVersion() : Contract.API_VERSION;
        if (apiVersion.isEmpty()) {
            throw new PaymentFlowConfigurationException("apiVersion must not be empty.");
        }

        Duration timeout = options.timeout() != null ? options.timeout() : Duration.ofSeconds(30);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new PaymentFlowConfigurationException("timeout must be a positive duration.");
        }

        int maxRetries = options.maxRetries() != null ? options.maxRetries() : 3;
        if (maxRetries < 0) {
            throw new PaymentFlowConfigurationException("maxRetries must not be negative.");
        }

        HttpClient httpClient = options.httpClient() != null
                ? options.httpClient()
                : HttpClient.newBuilder().connectTimeout(timeout).build();

        return new ClientConfig(apiKey, baseUri, apiVersion, timeout, maxRetries, httpClient);
    }

    public String apiKey() {
        return apiKey;
    }

    public URI baseUri() {
        return baseUri;
    }

    public String baseUrl() {
        return baseUri.toString();
    }

    public String apiVersion() {
        return apiVersion;
    }

    public Duration timeout() {
        return timeout;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public HttpClient httpClient() {
        return httpClient;
    }

    public String userAgent() {
        return USER_AGENT;
    }
}
