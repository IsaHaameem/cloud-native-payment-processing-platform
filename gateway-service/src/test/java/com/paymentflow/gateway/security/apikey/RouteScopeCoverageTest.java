package com.paymentflow.gateway.security.apikey;

import com.paymentflow.gateway.security.ApiScopes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate on M23.0's one real drift risk: the route-to-scope map and the scope set a
 * developer-portal session is granted are two answers to the same question, produced in
 * different files.
 *
 * <p>If a later milestone adds a {@code /v1} route with a scope that is not in
 * {@link ApiScopes#ALL}, the portal is silently locked out of it — a 403 on one screen,
 * possibly milestones after the change that caused it, with nothing pointing at the cause.
 * {@code ApiScopes} makes that hard by being the only declaration of the vocabulary; this
 * makes it fail loudly instead of quietly.
 *
 * <p>The paths below mirror the {@code /v1} route predicates in {@code application.yaml}
 * deliberately, rather than being derived from the filter's own private constants: a test
 * that reads the same list the code reads would agree with it however wrong both were.
 */
class RouteScopeCoverageTest {

    @ParameterizedTest(name = "{0} {1} requires a scope the portal actually holds")
    @CsvSource({
            "GET,     /v1/payments",
            "GET,     /v1/payments/pay_123",
            "POST,    /v1/payments",
            "POST,    /v1/payments/pay_123/capture",
            "POST,    /v1/payments/pay_123/refund",
            "POST,    /v1/payments/pay_123/void",
            "GET,     /v1/refunds",
            "GET,     /v1/refunds/re_123",
            "GET,     /v1/balance",
            "GET,     /v1/balance_transactions",
            "GET,     /v1/events",
            "GET,     /v1/events/evt_123",
            "GET,     /v1/webhook_endpoints",
            "POST,    /v1/webhook_endpoints",
            "PATCH,   /v1/webhook_endpoints/we_123",
            "DELETE,  /v1/webhook_endpoints/we_123",
            "POST,    /v1/webhook_endpoints/we_123/rotate_secret",
            "GET,     /v1/webhook_deliveries",
            "GET,     /v1/webhook_deliveries/wd_123",
            "POST,    /v1/webhook_deliveries/wd_123/replay",
            "GET,     /v1/analytics/payments",
            "GET,     /v1/request_logs",
            "GET,     /v1/usage",
    })
    void everyScopeARouteCanRequireIsOneASessionIsGranted(String method, String path) {
        String required = ApiKeyAuthenticationWebFilter.requiredScopeFor(HttpMethod.valueOf(method.trim()), path);

        assertThat(required)
                .as("%s %s maps to a scope, so a session must hold it", method, path)
                .isNotNull();
        assertThat(ApiScopes.ALL).contains(required);
    }

    @ParameterizedTest(name = "{0} {1} requires no specific scope")
    @CsvSource({
            "GET,  /v1/test/cards",
            "GET,  /v1/test/decisions",
            "POST, /v1/test/simulations",
    })
    void theSandboxRoutesStillRequireNoSpecificScope(String method, String path) {
        // Not an oversight: simulation controls are confined to test mode by sandbox-service
        // itself, and the card catalogue is public reference data. Asserted so that "null"
        // stays a decision rather than becoming a gap nobody noticed.
        assertThat(ApiKeyAuthenticationWebFilter.requiredScopeFor(HttpMethod.valueOf(method.trim()), path)).isNull();
    }

    @Test
    void theSessionScopeSetIsTheWholeVocabularyAndNotTheWildcard() {
        assertThat(ApiScopes.ALL).containsExactlyInAnyOrder(
                ApiScopes.PAYMENTS_READ, ApiScopes.PAYMENTS_WRITE, ApiScopes.WEBHOOKS_MANAGE,
                ApiScopes.BALANCE_READ, ApiScopes.EVENTS_READ, ApiScopes.ANALYTICS_READ, ApiScopes.LOGS_READ);
        assertThat(ApiScopes.ALL).doesNotContain(ApiScopes.WILDCARD);
    }

    @Test
    void aScopeCsvRoundTripsThroughTheHeaderDelimiter() {
        // The scopes header is comma-joined and comma-split; a scope name containing the
        // delimiter would silently become two scopes, one of them meaningless.
        assertThat(ApiScopes.ALL).allSatisfy(scope -> assertThat(scope).doesNotContain(","));
    }
}
