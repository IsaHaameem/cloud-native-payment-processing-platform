package simulations;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import simulations.support.MerchantFeeder;
import simulations.support.PaymentChains;
import simulations.support.Protocol;

import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Deliberate contention (M14 requirement: verify ledger consistency and
 * idempotency under concurrency) — many concurrent virtual users forced onto
 * a tiny pool of just 3 merchants (MerchantFeeder#hotPool), so
 * transaction-service's shared PLATFORM_CLEARING/MERCHANT_PENDING/
 * MERCHANT_SETTLED accounts (M6) and analytics-service's per-merchant
 * aggregate row (M7) both genuinely contend on the same rows under real
 * concurrent load, exercising the optimistic-lock-retry paths
 * (`ledger_posting_retries_total`, M13) rather than each virtual user
 * quietly working on its own uncontended data.
 *
 * Not a throughput benchmark — a correctness-under-concurrency check. Pass
 * criterion is "every request that should succeed does" (checked per-request,
 * same as every other simulation) plus a manual post-run consistency check
 * (see the M14 changelog's verification steps: every fully-refunded
 * lifecycle's ledger accounts must net to zero, exactly like M6's own
 * original manual verification, now reproduced under real concurrent load).
 */
public class ConcurrentContentionSimulation extends Simulation {

    private static final int HOT_POOL_SIZE = 3;
    private static final int CONCURRENT_USERS = Integer.getInteger("concurrentUsers", 80);

    private final ScenarioBuilder scn = scenario("Concurrent contention: full payment lifecycle on 3 merchants")
            .feed(MerchantFeeder.hotPool(HOT_POOL_SIZE))
            .exec(PaymentChains.FULL_LIFECYCLE);

    {
        setUp(scn.injectOpen(rampUsers(CONCURRENT_USERS).during(5)))
                .protocols(Protocol.HTTP_PROTOCOL);
    }
}
