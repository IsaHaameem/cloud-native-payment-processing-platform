package simulations;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import simulations.support.MerchantFeeder;
import simulations.support.PaymentChains;
import simulations.support.Protocol;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static java.time.Duration.ofSeconds;

/**
 * Steady-state traffic (M14) — a constant arrival rate against already-seeded
 * merchants (run SeedMerchantsSimulation first), each virtual user driving
 * one full create->authorize->capture->refund lifecycle. This is the
 * platform's realistic baseline load: what capacity/latency numbers actually
 * mean for "normal" traffic, as opposed to Burst/Contention's deliberately
 * abnormal shapes.
 *
 * Overridable via -DusersPerSec=N -DdurationSeconds=N for a longer/heavier
 * run without editing code.
 */
public class SustainedLoadSimulation extends Simulation {

    private static final double USERS_PER_SEC = Double.parseDouble(System.getProperty("usersPerSec", "5"));
    private static final int DURATION_SECONDS = Integer.getInteger("durationSeconds", 120);

    private final ScenarioBuilder scn = scenario("Sustained load: full payment lifecycle")
            .feed(MerchantFeeder.circular())
            .exec(PaymentChains.FULL_LIFECYCLE);

    {
        setUp(scn.injectOpen(constantUsersPerSec(USERS_PER_SEC).during(ofSeconds(DURATION_SECONDS))))
                .protocols(Protocol.HTTP_PROTOCOL)
                .assertions(
                        global().successfulRequests().percent().gte(99.0),
                        global().responseTime().percentile(95).lte(2000)
                );
    }
}
