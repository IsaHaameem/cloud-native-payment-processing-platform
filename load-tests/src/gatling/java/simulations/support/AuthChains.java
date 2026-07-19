package simulations.support;

import io.gatling.javaapi.core.ChainBuilder;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.http.HttpDsl.status;
import static io.gatling.javaapi.http.HttpDsl.http;

/**
 * Registration + login (identity-service, via the gateway). Every load-test
 * user shares one fixed password — safe, since each user's email is unique
 * per iteration (Feeders#emailFeeder) and this password never protects
 * anything beyond throwaway load-test data.
 */
public final class AuthChains {

    private AuthChains() {
    }

    public static final String LOAD_TEST_PASSWORD = "LoadTest123!";

    /** Feeds a fresh email, registers, logs in, and saves "email"/"token" in the session. */
    public static final ChainBuilder REGISTER_AND_LOGIN = feed(Feeders.emailFeeder())
            .exec(http("Register")
                    .post("/api/v1/auth/register")
                    .body(StringBody(session -> """
                            {"email":"%s","password":"%s","fullName":"Load Test User"}
                            """.formatted(session.getString("email"), LOAD_TEST_PASSWORD)))
                    .check(status().is(201)))
            .exec(http("Login")
                    .post("/api/v1/auth/login")
                    .body(StringBody(session -> """
                            {"email":"%s","password":"%s"}
                            """.formatted(session.getString("email"), LOAD_TEST_PASSWORD)))
                    .check(status().is(200))
                    .check(jsonPath("$.accessToken").saveAs("token")));

    /** Deliberately wrong password against an already-registered load-test email — expects 401 (failure-scenario coverage). */
    public static final ChainBuilder LOGIN_WITH_WRONG_PASSWORD = exec(http("Login (wrong password)")
            .post("/api/v1/auth/login")
            .body(StringBody(session -> """
                    {"email":"%s","password":"DefinitelyWrongPassword!"}
                    """.formatted(session.getString("email"))))
            .check(status().is(401)));
}
