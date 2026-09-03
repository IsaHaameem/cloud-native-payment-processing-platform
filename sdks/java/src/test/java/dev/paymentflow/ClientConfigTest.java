package dev.paymentflow;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigTest {

    @Test
    void aMissingKeyFailsWhenTheClientIsBuiltNotOnTheFirstCall() {
        PaymentFlowConfigurationException e = assertThrows(PaymentFlowConfigurationException.class,
                () -> PaymentFlow.builder().apiKey(null).baseUrl("https://example.test").build());
        assertTrue(e.getMessage().contains("PAYMENTFLOW_API_KEY"));
    }

    @Test
    void aWhitespaceWrappedKeyIsRejectedNotTrimmed() {
        assertThrows(PaymentFlowConfigurationException.class,
                () -> PaymentFlow.builder().apiKey(" sk_test_x ").build());
    }

    @Test
    void aNonAbsoluteBaseUrlIsRejected() {
        assertThrows(PaymentFlowConfigurationException.class,
                () -> PaymentFlow.builder().apiKey("sk_test_x").baseUrl("/v1").build());
    }

    @Test
    void aNonPositiveTimeoutIsRejected() {
        assertThrows(PaymentFlowConfigurationException.class,
                () -> PaymentFlow.builder().apiKey("sk_test_x").timeout(Duration.ZERO).build());
    }

    @Test
    void aNegativeRetryBudgetIsRejected() {
        assertThrows(PaymentFlowConfigurationException.class,
                () -> PaymentFlow.builder().apiKey("sk_test_x").maxRetries(-1).build());
    }

    @Test
    void theDefaultsAreTheAgreedOnes() {
        PaymentFlow client = PaymentFlow.builder().apiKey("sk_test_x").build();
        assertEquals("https://api.paymentflow.dev", client.baseUrl());
        assertEquals("2026-08-01", client.apiVersion());
    }

    @Test
    void aTrailingSlashOnTheBaseUrlIsStripped() {
        PaymentFlow client = PaymentFlow.builder().apiKey("sk_test_x").baseUrl("https://api.example.test/").build();
        assertEquals("https://api.example.test", client.baseUrl());
    }
}
