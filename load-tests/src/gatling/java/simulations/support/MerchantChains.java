package simulations.support;

import io.gatling.javaapi.core.ChainBuilder;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.status;
import static io.gatling.javaapi.http.HttpDsl.http;

/** Merchant onboarding (merchant-service, via the gateway) — requires "token"/"email" already in session (AuthChains). */
public final class MerchantChains {

    private MerchantChains() {
    }

    /** Onboards a merchant for the session's authenticated user and saves "merchantId". */
    public static final ChainBuilder ONBOARD = exec(
            http("Onboard merchant")
                    .post("/api/v1/merchants")
                    .header("Authorization", "Bearer #{token}")
                    .body(StringBody(session -> """
                            {"businessName":"Load Test Merchant","contactEmail":"%s"}
                            """.formatted(session.getString("email"))))
                    .check(status().is(201))
                    .check(jsonPath("$.merchant.id").saveAs("merchantId")));
}
