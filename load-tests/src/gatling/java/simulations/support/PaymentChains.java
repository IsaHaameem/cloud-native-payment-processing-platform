package simulations.support;

import io.gatling.javaapi.core.ChainBuilder;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.status;
import static io.gatling.javaapi.http.HttpDsl.http;

/**
 * Payment lifecycle mutations (payment-service, via the gateway) — every
 * chain requires "token"/"merchantId" already in session (AuthChains +
 * MerchantChains, or the seeded merchant-pool feeder). One fixed amount
 * (5000 minor units, USD) for every load-test payment — deterministic,
 * avoids threading an extra session variable through create->refund purely
 * to remember what amount was charged.
 */
public final class PaymentChains {

    private PaymentChains() {
    }

    public static final long AMOUNT_MINOR = 5000L;
    public static final String CURRENCY = "USD";

    /** Creates a payment (status CREATED) and saves "paymentId". */
    public static final ChainBuilder CREATE = feed(Feeders.idempotencyKeyFeeder())
            .exec(http("Create payment")
                    .post("/api/v1/payments")
                    .header("Authorization", "Bearer #{token}")
                    .header("Idempotency-Key", "#{idempotencyKey}")
                    .body(StringBody("""
                            {"amountMinor":%d,"currency":"%s","description":"Gatling load test"}
                            """.formatted(AMOUNT_MINOR, CURRENCY)))
                    .check(status().is(201))
                    .check(jsonPath("$.id").saveAs("paymentId")));

    public static final ChainBuilder AUTHORIZE = feed(Feeders.idempotencyKeyFeeder())
            .exec(http("Authorize payment")
                    .post("/api/v1/payments/#{paymentId}/authorize")
                    .header("Authorization", "Bearer #{token}")
                    .header("Idempotency-Key", "#{idempotencyKey}")
                    .check(status().is(200)));

    public static final ChainBuilder CAPTURE = feed(Feeders.idempotencyKeyFeeder())
            .exec(http("Capture payment")
                    .post("/api/v1/payments/#{paymentId}/capture")
                    .header("Authorization", "Bearer #{token}")
                    .header("Idempotency-Key", "#{idempotencyKey}")
                    .check(status().is(200)));

    public static final ChainBuilder REFUND = feed(Feeders.idempotencyKeyFeeder())
            .exec(http("Refund payment")
                    .post("/api/v1/payments/#{paymentId}/refund")
                    .header("Authorization", "Bearer #{token}")
                    .header("Idempotency-Key", "#{idempotencyKey}")
                    .body(StringBody("""
                            {"amountMinor":%d}
                            """.formatted(AMOUNT_MINOR)))
                    .check(status().is(200)));

    /** Full happy-path lifecycle: create -> authorize -> capture -> refund. */
    public static final ChainBuilder FULL_LIFECYCLE = exec(CREATE).exec(AUTHORIZE).exec(CAPTURE).exec(REFUND);
}
