package simulations;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import simulations.support.AuthChains;
import simulations.support.MerchantChains;
import simulations.support.PaymentChains;
import simulations.support.Protocol;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Fast sanity check (M14) — one user, one full lifecycle, no load shape. Not
 * a performance benchmark: the point is "does the suite itself actually work
 * against a real running platform" before trusting any of the heavier
 * simulations' numbers. Run first, every time, before a real benchmark run.
 */
public class SmokeSimulation extends Simulation {

    private final ScenarioBuilder scn = scenario("Smoke: full payment lifecycle")
            .exec(AuthChains.REGISTER_AND_LOGIN)
            .exec(MerchantChains.ONBOARD)
            .exec(PaymentChains.FULL_LIFECYCLE);

    {
        setUp(scn.injectOpen(atOnceUsers(1)))
                .protocols(Protocol.HTTP_PROTOCOL);
    }
}
