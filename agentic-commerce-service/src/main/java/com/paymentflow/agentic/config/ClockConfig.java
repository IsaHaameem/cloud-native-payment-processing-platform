package com.paymentflow.agentic.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * A single injected {@link Clock} rather than scattered {@code Instant.now()} calls.
 *
 * <p>Checkout expiry, approval expiry and idempotency-key derivation all read the time, and
 * two of those three are things a test needs to move. Injecting the clock is what lets an
 * expiry test assert on expiry rather than on {@code Thread.sleep}.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
