package simulations.support;

import io.gatling.javaapi.core.ChainBuilder;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Deliberate failure-path coverage (M14 requirement #2/#4) — every chain here
 * expects a specific non-2xx outcome and treats *that* status code as success
 * for the simulation's own pass/fail purposes (Gatling counts a checked
 * status mismatch as a failed request either way, so asserting the expected
 * error code is what makes these scenarios "pass").
 */
public final class FailureChains {

    private FailureChains() {
    }

    /**
     * Same Idempotency-Key, two genuinely different request bodies — payment-service's
     * own idempotency guard (D34/IdempotencyKeyReusedException) must reject the
     * second with 409, not silently accept or corrupt the first result.
     */
    public static final ChainBuilder IDEMPOTENCY_KEY_REUSE_CONFLICT = feed(Feeders.idempotencyKeyFeeder())
            .exec(http("Create payment (first use of key)")
                    .post("/api/v1/payments")
                    .header("Authorization", "Bearer #{token}")
                    .header("Idempotency-Key", "#{idempotencyKey}")
                    .body(StringBody("""
                            {"amountMinor":1000,"currency":"USD","description":"first body"}
                            """))
                    .check(status().is(201)))
            .exec(http("Create payment (same key, different body -- expect 409)")
                    .post("/api/v1/payments")
                    .header("Authorization", "Bearer #{token}")
                    .header("Idempotency-Key", "#{idempotencyKey}")
                    .body(StringBody("""
                            {"amountMinor":2000,"currency":"USD","description":"different body, same key"}
                            """))
                    .check(status().is(409)));

    /**
     * Same Idempotency-Key, identical body -- a genuine client retry. Must
     * replay the exact same stored response (200/201, same paymentId), not
     * create a second payment.
     *
     * <p>The replay check is a manual session comparison, not
     * {@code jsonPath("$.id").is("#{originalPaymentId}")} — that EL-string
     * form compiles into a single {@code Expression} object shared across
     * every concurrent virtual user running this {@code static final} chain,
     * and under real concurrency (10 users via {@code atOnceUsers}) it
     * produced false-negative mismatches on every single user even though
     * the platform's actual behavior was verified correct both by manual
     * curl reproduction and by an isolated debug simulation using this same
     * saveAs+manual-comparison approach (which matched 10/10 under identical
     * concurrent load). Root-caused as a Gatling DSL/harness issue, not a
     * payment-service defect — see the M14 changelog for the full diagnosis.
     */
    public static final ChainBuilder IDEMPOTENCY_KEY_REPLAY = feed(Feeders.idempotencyKeyFeeder())
            .exec(http("Create payment (original)")
                    .post("/api/v1/payments")
                    .header("Authorization", "Bearer #{token}")
                    .header("Idempotency-Key", "#{idempotencyKey}")
                    .body(StringBody("""
                            {"amountMinor":1500,"currency":"USD","description":"replay test"}
                            """))
                    .check(status().is(201))
                    .check(jsonPath("$.id").saveAs("originalPaymentId")))
            .exec(http("Create payment (replay, identical body)")
                    .post("/api/v1/payments")
                    .header("Authorization", "Bearer #{token}")
                    .header("Idempotency-Key", "#{idempotencyKey}")
                    .body(StringBody("""
                            {"amountMinor":1500,"currency":"USD","description":"replay test"}
                            """))
                    .check(status().is(201))
                    .check(jsonPath("$.id").saveAs("replayPaymentId")))
            .exec(session -> {
                String original = session.getString("originalPaymentId");
                String replay = session.getString("replayPaymentId");
                if (original == null || !original.equals(replay)) {
                    return session.markAsFailed();
                }
                return session;
            });
}
