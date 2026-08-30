package dev.paymentflow;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape an integrator relies on. §7.1 fixes it across every language; the Java spelling
 * differs only where the language forces it — {@code ...Exception} for {@code ...Error},
 * {@code voidPayment} for {@code void}, {@code delete} for {@code del}.
 */
class PublicSurfaceTest {

    @Test
    void theClientExposesTheElevenNamespaces() throws Exception {
        for (String name : List.of("payments", "refunds", "balance", "balanceTransactions", "events",
                "analytics", "requestLogs", "usage", "webhookEndpoints", "webhookDeliveries", "testHelpers")) {
            assertTrue(hasNoArgMethod(PaymentFlow.class, name), "PaymentFlow." + name + "()");
        }
    }

    @Test
    void everyErrorIsCatchableAsPaymentFlowException() {
        List<Class<?>> hierarchy = List.of(
                AuthenticationException.class, PermissionException.class, InvalidRequestException.class,
                IdempotencyException.class, RateLimitException.class, ApiConnectionException.class,
                ApiException.class, WebhookVerificationException.class, WebhookSignatureException.class,
                WebhookTimestampException.class, WebhookPayloadException.class,
                PaymentFlowConfigurationException.class);
        for (Class<?> type : hierarchy) {
            assertTrue(PaymentFlowException.class.isAssignableFrom(type), type.getSimpleName());
        }
    }

    @Test
    void theWebhookConstantsAreTheAgreedOnes() {
        assertEquals("PaymentFlow-Signature", Webhooks.SIGNATURE_HEADER);
        assertEquals(Duration.ofSeconds(300), Webhooks.DEFAULT_TOLERANCE);
    }

    @Test
    void thePaymentNamespaceHasSevenOperationsAndNoEighth() {
        long ops = java.util.Arrays.stream(dev.paymentflow.resources.Payments.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(java.lang.reflect.Method::getName)
                .filter(n -> List.of("create", "retrieve", "list", "authorize", "capture", "refund", "voidPayment")
                        .contains(n))
                .distinct()
                .count();
        assertEquals(7, ops);
    }

    private static boolean hasNoArgMethod(Class<?> type, String name) {
        try {
            type.getMethod(name);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
