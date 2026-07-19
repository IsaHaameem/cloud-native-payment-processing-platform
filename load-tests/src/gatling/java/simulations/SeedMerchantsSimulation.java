package simulations;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import simulations.support.AuthChains;
import simulations.support.MerchantChains;
import simulations.support.MerchantPool;
import simulations.support.Protocol;

import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Run once before any of the main benchmarks (M14) — registers, logs in, and
 * onboards a pool of merchants, writing (token, merchantId) pairs to
 * data/merchants.csv. Every other simulation feeds from that pool instead of
 * registering a brand-new user per iteration: real payment-processor traffic
 * is dominated by *already-onboarded* merchants making payment calls, not by
 * registration — separating "seed the pool" from "load-test the payment
 * hot path" measures each accurately instead of conflating registration
 * overhead into every payment-throughput number.
 *
 * <pre>./gradlew :load-tests:gatlingRun --simulation simulations.SeedMerchantsSimulation</pre>
 */
public class SeedMerchantsSimulation extends Simulation {

    private static final int POOL_SIZE = Integer.getInteger("poolSize", 100);

    private final ScenarioBuilder scn = scenario("Seed merchant pool")
            .exec(AuthChains.REGISTER_AND_LOGIN)
            .exec(MerchantChains.ONBOARD)
            .exec(session -> {
                MerchantPool.append(session.getString("token"), session.getString("merchantId"));
                return session;
            });

    {
        MerchantPool.reset();
        setUp(scn.injectOpen(rampUsers(POOL_SIZE).during(20)))
                .protocols(Protocol.HTTP_PROTOCOL);
    }
}
