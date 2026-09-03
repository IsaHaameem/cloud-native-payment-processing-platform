package dev.paymentflow;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link RequestOptions.Builder#build()} applies the same two checks {@link ClientConfigTest}
 * proves for the client's own defaults — a per-call override is exactly as capable of being
 * wrong as the client-level default it overrides, and should fail on the same line: the one
 * that built it, not the first request that tries to use it.
 */
class RequestOptionsTest {

    @Test
    void aZeroTimeoutIsRejected() {
        assertThrows(PaymentFlowConfigurationException.class,
                () -> RequestOptions.builder().timeout(Duration.ZERO).build());
    }

    @Test
    void aNegativeTimeoutIsRejected() {
        assertThrows(PaymentFlowConfigurationException.class,
                () -> RequestOptions.builder().timeout(Duration.ofSeconds(-1)).build());
    }

    @Test
    void aNegativeRetryBudgetIsRejected() {
        assertThrows(PaymentFlowConfigurationException.class,
                () -> RequestOptions.builder().maxRetries(-1).build());
    }

    /**
     * Never calling {@link RequestOptions.Builder#timeout} — the common case, since most calls
     * vary nothing — leaves the field {@code null}, which means "use the client's" and must
     * keep building without error. The validation added here checks a *supplied* value; it must
     * not turn the no-override default into a configuration failure.
     */
    @Test
    void aNullTimeoutMeansNoOverrideAndIsNotRejected() {
        RequestOptions options = RequestOptions.builder().build();

        assertNull(options.timeout());
    }

    @Test
    void aValidTimeoutIsAccepted() {
        RequestOptions options = RequestOptions.builder().timeout(Duration.ofSeconds(5)).build();

        assertEquals(Duration.ofSeconds(5), options.timeout());
    }

    /**
     * 0 is the boundary the spec actually cares about — {@code maxRetries} "must be >= 0" is a
     * different rule from "must be > 0", and only asserting a valid positive number would not
     * catch the off-by-one of implementing the check as {@code <= 0}.
     */
    @Test
    void aZeroRetryBudgetIsAccepted() {
        RequestOptions options = RequestOptions.builder().maxRetries(0).build();

        assertEquals(0, options.maxRetries());
    }
}
