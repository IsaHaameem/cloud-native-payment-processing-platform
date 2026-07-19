package simulations.support;

import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.http.HttpDsl.http;

/**
 * Shared HTTP protocol config for every simulation (M14) — always through the
 * real edge (gateway-service, port 8080), exactly like a real external caller;
 * no simulation talks to a downstream service's own port directly. Overridable
 * via -DbaseUrl=... so the same suite can later point at a different host
 * without editing simulation code (e.g. a future AWS run).
 */
public final class Protocol {

    private Protocol() {
    }

    public static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8080");

    public static final HttpProtocolBuilder HTTP_PROTOCOL = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("PaymentFlow-LoadTest/1.0 (Gatling, M14)")
            .disableWarmUp();
}
