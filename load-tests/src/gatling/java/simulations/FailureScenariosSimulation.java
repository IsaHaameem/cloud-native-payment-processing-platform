package simulations;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import simulations.support.AuthChains;
import simulations.support.FailureChains;
import simulations.support.MerchantFeeder;
import simulations.support.Protocol;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.repeat;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Deliberate failure paths (M14 requirement #2/#4), run together as separate
 * populations in one simulation: bad credentials, idempotency-key conflict
 * and replay, and a single-session request burst intended to trip the
 * gateway's Redis-backed rate limiter (D24: replenishRate=20/s,
 * burstCapacity=40 per authenticated user). Every check here accepts the
 * *expected* failure status as success — a 401/409/429 in these scenarios is
 * the platform behaving correctly, not a defect.
 */
public class FailureScenariosSimulation extends Simulation {

    private final ScenarioBuilder badCredentials = scenario("Failure: wrong password")
            .exec(AuthChains.REGISTER_AND_LOGIN)
            .exec(AuthChains.LOGIN_WITH_WRONG_PASSWORD);

    private final ScenarioBuilder idempotencyConflict = scenario("Failure: idempotency-key reuse conflict")
            .feed(MerchantFeeder.circular())
            .exec(FailureChains.IDEMPOTENCY_KEY_REUSE_CONFLICT);

    private final ScenarioBuilder idempotencyReplay = scenario("Failure: idempotency-key replay")
            .feed(MerchantFeeder.circular())
            .exec(FailureChains.IDEMPOTENCY_KEY_REPLAY);

    /**
     * One authenticated session firing 100 GETs back-to-back with no pacing —
     * comfortably over the 20/s replenish + 40 burst-capacity budget for a
     * single `user:<sub>` rate-limit key (D24). 200 or 429 are both accepted
     * per-request (this scenario's job is to *trigger* the limiter, not
     * assert every single request's exact outcome); the real assertion is a
     * post-run check that at least one 429 actually occurred — see the M14
     * changelog's verification steps.
     */
    private final ScenarioBuilder rateLimitBurst = scenario("Failure: rate-limit burst")
            .feed(MerchantFeeder.circular())
            .exec(repeat(100).on(
                    exec(http("List payments (rapid-fire)")
                            .get("/api/v1/payments")
                            .header("Authorization", "Bearer #{token}")
                            .check(status().in(200, 429)))
            ));

    {
        setUp(
                badCredentials.injectOpen(atOnceUsers(10)),
                idempotencyConflict.injectOpen(atOnceUsers(10)),
                idempotencyReplay.injectOpen(atOnceUsers(10)),
                rateLimitBurst.injectOpen(atOnceUsers(3))
        ).protocols(Protocol.HTTP_PROTOCOL);
    }
}
