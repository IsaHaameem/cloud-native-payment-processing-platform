package com.paymentflow.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Platform default rate limits and quotas for API-key traffic (M20.5, D145/D146).
 *
 * <p><b>Test and live have different budgets deliberately.</b> Sandbox traffic is bursty and
 * experimental by nature — a developer looping over test cards is doing exactly what test mode
 * is for — while live traffic is real money and worth a more conservative ceiling. Separate
 * budgets also mean a load test in sandbox can never exhaust the allowance a merchant's
 * production traffic depends on, which is the isolation guarantee M16 established for data and
 * this extends to capacity.
 *
 * <p>The gateway owns these defaults rather than merchant-service, because merchant-service
 * knows what is <em>exceptional</em> about a merchant (D145's override columns), not what is
 * normal for everyone. Changing the normal case is then a configuration change, not a data
 * migration across every merchant row.
 *
 * @param enabled       master switch, so per-key limiting can be shed in an incident without a
 *                      rollback (D24's IP limiting keeps protecting the edge either way)
 * @param test          defaults applied to `sk_test_`/`pk_test_` traffic
 * @param live          defaults applied to `sk_live_`/`pk_live_` traffic
 */
@ConfigurationProperties(prefix = "paymentflow.gateway.api-key-rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Budget test,
        @DefaultValue Budget live) {

    /**
     * @param requestsPerSecond sustained refill rate of the token bucket
     * @param burst             bucket capacity — how far above the sustained rate a caller may
     *                          spike before being refused. A burst below the rate would make
     *                          the sustained rate unreachable, so it is validated, not assumed
     * @param dailyQuota        requests per UTC day; 0 disables the quota, leaving only the
     *                          bucket
     */
    public record Budget(
            @DefaultValue("50") int requestsPerSecond,
            @DefaultValue("100") int burst,
            @DefaultValue("500000") int dailyQuota) {

        public Budget {
            if (requestsPerSecond <= 0) {
                throw new IllegalArgumentException("requests-per-second must be positive");
            }
            if (burst < requestsPerSecond) {
                // A capacity below the refill rate silently caps throughput below the rate the
                // configuration advertises — the limit would not be what it says it is.
                throw new IllegalArgumentException("burst must be at least requests-per-second");
            }
            if (dailyQuota < 0) {
                throw new IllegalArgumentException("daily-quota must not be negative");
            }
        }
    }

    /** The budget for a mode, defaulting to the live (more conservative) one for anything unknown. */
    public Budget budgetFor(String mode) {
        return "test".equalsIgnoreCase(mode) ? test : live;
    }
}
