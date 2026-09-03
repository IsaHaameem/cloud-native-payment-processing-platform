package com.paymentflow.gateway.web;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /health} — the platform's public liveness probe.
 *
 * <p>One endpoint, at the edge, deliberately. The gateway is the only service exposed to the
 * internet, so it is the only place an external checker (Render, an uptime monitor) can reach;
 * putting a copy of this in each microservice would add nine more endpoints that nothing outside
 * the cluster could call. Per-service health is already covered and unchanged: every service
 * exposes Spring Boot Actuator's {@code /actuator/health} with Kubernetes-style
 * liveness/readiness probes enabled, which is what an orchestrator inside the cluster uses.
 *
 * <p><b>Deliberately shallow.</b> This answers one question — "is this process alive and serving
 * HTTP?" — and it must never answer any other. It touches no database, no Redis, no downstream
 * service, and allocates nothing per request (the body is a constant). That is the difference
 * between this and {@code /actuator/health}, which aggregates every registered
 * {@code HealthIndicator} and so reports DOWN when a dependency is degraded. A liveness probe
 * that fails on a dependency outage is a liveness probe that tells the platform to restart a
 * perfectly healthy process at the worst possible moment — use {@code /actuator/health} when you
 * want that aggregate answer, and this when you want to know whether the process is up.
 *
 * <p>Unauthenticated by necessity: an external monitor has no credentials. It is listed
 * explicitly in {@code SecurityConfig}'s second chain, beside the actuator probes — the gateway
 * otherwise fails closed, so an unlisted path 401s rather than leaking. Nothing here is
 * merchant-scoped, mode-dependent, or derived from any request input, so there is nothing for
 * the exposure to disclose beyond "the gateway is running", which the TCP handshake already
 * revealed.
 *
 * <p>{@code Cache-Control: no-store} because a cached 200 from an intermediary would keep
 * reporting a healthy gateway after it stopped being one.
 */
@RestController
public class HealthController {

    /** The response body. A record so the JSON shape is the type, not a map literal. */
    public record Health(String status) {}

    /**
     * Shared, immutable, and allocated once. A liveness endpoint is polled continuously for the
     * life of the deployment; there is no reason for it to produce garbage.
     */
    private static final Health OK = new Health("ok");

    /**
     * Answers {@code 200} with {@code {"status":"ok"}} whenever this process can serve a request
     * at all. There is no failure branch on purpose: reaching this method already proves the
     * only thing the endpoint claims. Anything that would make the answer "not ok" — the event
     * loop wedged, the process gone — also stops the response from being produced, which is
     * exactly the signal a checker acts on (timeout or connection refused, not a 503 body).
     *
     * <p>Returns a value rather than a {@code Mono}: it is already computed, so there is nothing
     * to defer and nothing that could block the event loop.
     */
    @GetMapping(path = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Health> health() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(OK);
    }
}
