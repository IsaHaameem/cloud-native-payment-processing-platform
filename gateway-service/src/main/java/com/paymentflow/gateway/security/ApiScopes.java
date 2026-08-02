package com.paymentflow.gateway.security;

import java.util.Set;

/**
 * The scope vocabulary the gateway enforces on the public {@code /v1} tier (§4.9).
 *
 * <p>Extracted in M23.0 because there are now two producers of a scope set and one
 * consumer of it. The API-key path takes a key's own granted scopes and checks them
 * against {@code ApiKeyAuthenticationWebFilter}'s route map; the developer-portal session
 * path has no key and must synthesise a set. If those two lived apart, adding a route with
 * a new scope would silently lock the portal out of it — the failure would surface as a 403
 * on one screen, milestones later, with nothing pointing at the cause.
 *
 * <p>{@link #ALL} is deliberately the enumerated set and <b>not</b> the {@code "*"}
 * wildcard. The wildcard short-circuits the gateway's scope check entirely, so granting it
 * to a session would mean the check stops running on exactly the traffic a human drives.
 * Naming every scope keeps the check live, and makes a future member role a subset of this
 * set rather than a different mechanism.
 */
public final class ApiScopes {

    public static final String PAYMENTS_READ = "payments:read";
    public static final String PAYMENTS_WRITE = "payments:write";
    public static final String WEBHOOKS_MANAGE = "webhooks:manage";
    public static final String BALANCE_READ = "balance:read";
    public static final String EVENTS_READ = "events:read";
    public static final String ANALYTICS_READ = "analytics:read";
    public static final String LOGS_READ = "logs:read";

    /** The wildcard a key's scope list may carry, which grants everything without being a scope. */
    public static final String WILDCARD = "*";

    /**
     * Every scope the platform defines — what a merchant's owner gets in the developer
     * portal, since M23's model is one user per merchant (§13-Q2) and that user is entitled
     * to everything their own merchant can do.
     */
    public static final Set<String> ALL = Set.of(
            PAYMENTS_READ, PAYMENTS_WRITE, WEBHOOKS_MANAGE, BALANCE_READ, EVENTS_READ, ANALYTICS_READ, LOGS_READ);

    private ApiScopes() {
    }
}
