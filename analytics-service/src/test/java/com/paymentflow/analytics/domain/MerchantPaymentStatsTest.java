package com.paymentflow.analytics.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantPaymentStatsTest {

    @Test
    void newStatsStartAtAllZeros() {
        MerchantPaymentStats stats = MerchantPaymentStats.open(UUID.randomUUID(), "USD");

        assertThat(stats.getCreatedCount()).isZero();
        assertThat(stats.getAuthorizedCount()).isZero();
        assertThat(stats.getCapturedCount()).isZero();
        assertThat(stats.getRefundedCount()).isZero();
        assertThat(stats.getVoidedCount()).isZero();
        assertThat(stats.getTotalCapturedAmountMinor()).isZero();
        assertThat(stats.getTotalRefundedAmountMinor()).isZero();
    }

    @Test
    void incrementCreatedBumpsOnlyCreatedCount() {
        MerchantPaymentStats stats = MerchantPaymentStats.open(UUID.randomUUID(), "USD");

        stats.incrementCreated();
        stats.incrementCreated();

        assertThat(stats.getCreatedCount()).isEqualTo(2);
        assertThat(stats.getAuthorizedCount()).isZero();
    }

    @Test
    void incrementCapturedBumpsCountAndAccumulatesAmount() {
        MerchantPaymentStats stats = MerchantPaymentStats.open(UUID.randomUUID(), "USD");

        stats.incrementCaptured(5000);
        stats.incrementCaptured(3000);

        assertThat(stats.getCapturedCount()).isEqualTo(2);
        assertThat(stats.getTotalCapturedAmountMinor()).isEqualTo(8000);
    }

    @Test
    void incrementRefundedBumpsCountAndAccumulatesAmount() {
        MerchantPaymentStats stats = MerchantPaymentStats.open(UUID.randomUUID(), "USD");

        stats.incrementRefunded(2000);

        assertThat(stats.getRefundedCount()).isEqualTo(1);
        assertThat(stats.getTotalRefundedAmountMinor()).isEqualTo(2000);
    }

    @Test
    void incrementAuthorizedAndVoidedBumpTheirOwnCounters() {
        MerchantPaymentStats stats = MerchantPaymentStats.open(UUID.randomUUID(), "USD");

        stats.incrementAuthorized();
        stats.incrementVoided();

        assertThat(stats.getAuthorizedCount()).isEqualTo(1);
        assertThat(stats.getVoidedCount()).isEqualTo(1);
    }
}
