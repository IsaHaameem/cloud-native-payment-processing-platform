package com.paymentflow.sandbox.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for D104's live-mode simulated acquirer (M17.7, §8.4) — "configurable per
 * environment," per its own design brief. {@code declineRate}/{@code errorRate} are
 * each a probability in {@code [0, 1)}; a live decision declines if its outcome draw
 * falls below {@code declineRate}, errors if it falls in the next {@code errorRate}
 * slice, and approves otherwise (see {@link DecisionEngine#decideLive}).
 * {@code latencyMeanMs}/{@code latencyStdDevMs} describe a small, realistic response
 * delay — nothing like the dramatic, developer-requested test-mode scenarios
 * (§8.1's {@code pm_card_slow}, up to the platform's 10s ceiling).
 */
@ConfigurationProperties(prefix = "paymentflow.simulated-acquirer")
public record SimulatedAcquirerProperties(double declineRate, double errorRate, int latencyMeanMs,
                                          int latencyStdDevMs) {
}
