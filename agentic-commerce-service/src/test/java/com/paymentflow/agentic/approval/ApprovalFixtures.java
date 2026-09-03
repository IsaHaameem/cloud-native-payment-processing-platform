package com.paymentflow.agentic.approval;

import com.paymentflow.agentic.policy.PolicyOperation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The ids, bindings and clock the approval tests share.
 *
 * <p>Every time-sensitive assertion here moves a clock rather than sleeping. An expiry test
 * that sleeps is a test that either takes thirty minutes or asserts against a TTL nobody would
 * ship — {@code MutableClock} is what lets the real thirty-minute default be the thing under
 * test.
 */
final class ApprovalFixtures {

    static final UUID MERCHANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER_MERCHANT_ID = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
    static final UUID CONVERSATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID ACTION_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    static final UUID CHECKOUT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    static final UUID PAYMENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID OTHER_PAYMENT_ID = UUID.fromString("44444444-9999-9999-9999-999999999999");

    static final String MODE = "test";
    static final String CURRENCY = "INR";
    static final long REFUND_AMOUNT = 250_000L;

    static final Instant T0 = Instant.parse("2026-08-22T10:00:00Z");

    private ApprovalFixtures() {
    }

    /** The refund binding used throughout: one payment, one amount, one currency. */
    static ApprovalBinding refundBinding() {
        return refundBinding(REFUND_AMOUNT);
    }

    static ApprovalBinding refundBinding(long amountMinor) {
        return new ApprovalBinding(MERCHANT_ID, MODE, PolicyOperation.REFUND_CREATE, null, PAYMENT_ID,
                amountMinor, CURRENCY);
    }

    static Approval pendingRefund(Duration ttl) {
        return Approval.request(ACTION_ID, CONVERSATION_ID, "request_refund", refundBinding(),
                "Amount 250000 is above the 100000 approval threshold.", T0.plus(ttl));
    }

    /** A clock a test can move forward, so a thirty-minute TTL can be tested in microseconds. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            this.now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
