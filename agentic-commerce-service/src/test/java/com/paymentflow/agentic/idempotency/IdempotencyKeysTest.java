package com.paymentflow.agentic.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The derivation's contract: same logical action, same key — and every other tuple, a
 * different one.
 *
 * <p>The injectivity test at the bottom is the one that matters most. The other four assert
 * that the obvious things differ; that one asserts that the <em>non</em>-obvious things do
 * too, which is where a naive concatenation would quietly fail.
 */
class IdempotencyKeysTest {

    private static final UUID CONVERSATION = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_CONVERSATION = UUID.fromString("22222222-9999-9999-9999-999999999999");
    private static final UUID CHECKOUT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_CHECKOUT = UUID.fromString("33333333-9999-9999-9999-999999999999");
    private static final String TOOL = "complete_checkout";

    @Test
    @DisplayName("same conversation, tool, checkout and step produce the same key")
    void sameTupleSameKey() {
        String first = IdempotencyKeys.forStep(CONVERSATION, TOOL, CHECKOUT, "authorize");
        String second = IdempotencyKeys.forStep(CONVERSATION, TOOL, CHECKOUT, "authorize");

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("a different step produces a different key")
    void differentStepDifferentKey() {
        assertThat(IdempotencyKeys.forStep(CONVERSATION, TOOL, CHECKOUT, "authorize"))
                .isNotEqualTo(IdempotencyKeys.forStep(CONVERSATION, TOOL, CHECKOUT, "capture"));
    }

    @Test
    @DisplayName("a different checkout produces a different key")
    void differentCheckoutDifferentKey() {
        assertThat(IdempotencyKeys.forStep(CONVERSATION, TOOL, CHECKOUT, "authorize"))
                .isNotEqualTo(IdempotencyKeys.forStep(CONVERSATION, TOOL, OTHER_CHECKOUT, "authorize"));
    }

    @Test
    @DisplayName("a different conversation produces a different key")
    void differentConversationDifferentKey() {
        assertThat(IdempotencyKeys.forStep(CONVERSATION, TOOL, CHECKOUT, "authorize"))
                .isNotEqualTo(IdempotencyKeys.forStep(OTHER_CONVERSATION, TOOL, CHECKOUT, "authorize"));
    }

    @Test
    @DisplayName("a different tool produces a different key")
    void differentToolDifferentKey() {
        assertThat(IdempotencyKeys.forStep(CONVERSATION, TOOL, CHECKOUT, "authorize"))
                .isNotEqualTo(IdempotencyKeys.forStep(CONVERSATION, "request_refund", CHECKOUT, "authorize"));
    }

    @Test
    @DisplayName("a refund's key varies with the amount, per AD-12")
    void refundKeyIsAmountSensitive() {
        assertThat(IdempotencyKeys.forRefund(CONVERSATION, "request_refund", CHECKOUT, 50_000L))
                .isNotEqualTo(IdempotencyKeys.forRefund(CONVERSATION, "request_refund", CHECKOUT, 50_001L));
        assertThat(IdempotencyKeys.forRefund(CONVERSATION, "request_refund", CHECKOUT, 50_000L))
                .isEqualTo(IdempotencyKeys.forRefund(CONVERSATION, "request_refund", CHECKOUT, 50_000L));
    }

    @Test
    @DisplayName("two different logical tuples cannot serialize to one key — the boundary-shift case")
    void framingIsInjective() {
        // Plain concatenation collapses every one of these pairs. Length framing does not.
        List<String[]> ambiguousPairs = List.of(
                new String[] {"ab", "c", "a", "bc"},
                new String[] {"a", "", "", "a"},
                new String[] {"x:y", "z", "x", "y:z"},
                new String[] {"1", "23", "12", "3"});

        for (String[] pair : ambiguousPairs) {
            String left = IdempotencyKeys.derive(CONVERSATION, pair[0], CHECKOUT, pair[1]);
            String right = IdempotencyKeys.derive(CONVERSATION, pair[2], CHECKOUT, pair[3]);

            assertThat(left)
                    .as("tool=%s step=%s must not collide with tool=%s step=%s",
                            pair[0], pair[1], pair[2], pair[3])
                    .isNotEqualTo(right);
        }
    }

    @Test
    @DisplayName("an absent component is distinct from an empty one")
    void nullIsNotEmpty() {
        assertThat(IdempotencyKeys.derive(CONVERSATION, TOOL, null, "authorize"))
                .isNotEqualTo(IdempotencyKeys.derive(CONVERSATION, TOOL, CHECKOUT, "authorize"));
        assertThat(IdempotencyKeys.derive(CONVERSATION, TOOL, CHECKOUT, null))
                .isNotEqualTo(IdempotencyKeys.derive(CONVERSATION, TOOL, CHECKOUT, ""));
    }

    @Test
    @DisplayName("a full sweep of neighbouring tuples yields no collisions")
    void noCollisionsAcrossASweep() {
        Set<String> keys = new HashSet<>();
        int expected = 0;
        for (UUID conversation : List.of(CONVERSATION, OTHER_CONVERSATION)) {
            for (String tool : List.of("complete_checkout", "request_refund", "create_checkout")) {
                for (UUID resource : List.of(CHECKOUT, OTHER_CHECKOUT)) {
                    for (String step : List.of("create", "authorize", "capture", "")) {
                        keys.add(IdempotencyKeys.derive(conversation, tool, resource, step));
                        expected++;
                    }
                }
            }
        }
        assertThat(keys).hasSize(expected);
    }

    @Test
    @DisplayName("the key fits the platform's Idempotency-Key column and is recognisably ours")
    void keyShapeIsAcceptableToThePlatform() {
        String key = IdempotencyKeys.forStep(CONVERSATION, TOOL, CHECKOUT, "authorize");

        assertThat(key).startsWith("agt_").hasSize(68).matches("agt_[0-9a-f]{64}");
        assertThat(key.length()).as("payment-service stores it in varchar(255)").isLessThanOrEqualTo(255);
        assertThat(key.length()).as("this service stores it in varchar(128)").isLessThanOrEqualTo(128);
    }
}
