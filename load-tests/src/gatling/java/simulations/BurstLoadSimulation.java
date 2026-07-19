package simulations;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import simulations.support.MerchantFeeder;
import simulations.support.PaymentChains;
import simulations.support.Protocol;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static java.time.Duration.ofSeconds;

/**
 * Sudden spike then drop (M14) — the traffic shape a real payment platform
 * sees around a flash sale or a retry storm, not steady growth. Exercises
 * whether the platform degrades gracefully under a sudden load multiplier
 * (rate limiter, connection pools, Resilience4j bulkhead sizing) rather than
 * falling over, and whether it recovers cleanly once the spike passes.
 *
 * Shape: low baseline -> fast ramp to a high peak -> hold briefly -> fast
 * ramp back down. Overridable via -DpeakUsersPerSec=N.
 */
public class BurstLoadSimulation extends Simulation {

    private static final double BASELINE_USERS_PER_SEC = 2;
    private static final double PEAK_USERS_PER_SEC = Double.parseDouble(System.getProperty("peakUsersPerSec", "60"));

    private final ScenarioBuilder scn = scenario("Burst load: full payment lifecycle")
            .feed(MerchantFeeder.circular())
            .exec(PaymentChains.FULL_LIFECYCLE);

    {
        setUp(scn.injectOpen(
                        constantUsersPerSec(BASELINE_USERS_PER_SEC).during(ofSeconds(15)),
                        rampUsersPerSec(BASELINE_USERS_PER_SEC).to(PEAK_USERS_PER_SEC).during(ofSeconds(10)),
                        constantUsersPerSec(PEAK_USERS_PER_SEC).during(ofSeconds(20)),
                        rampUsersPerSec(PEAK_USERS_PER_SEC).to(BASELINE_USERS_PER_SEC).during(ofSeconds(10)),
                        constantUsersPerSec(BASELINE_USERS_PER_SEC).during(ofSeconds(15))
                ))
                .protocols(Protocol.HTTP_PROTOCOL);
    }
}
