package com.paymentflow.agentic.web;

import com.paymentflow.agentic.config.AgenticProperties;
import com.paymentflow.common.exception.ForbiddenException;
import com.paymentflow.common.exception.UnauthorizedException;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guard between the portal proxy's asserted context and everything the agentic API does.
 *
 * <p>Every financial control downstream assumes the merchant and mode are trustworthy. This is
 * where that becomes true: they come from the HMAC-verified context and are checked against
 * what this deployment is actually allowed to act as, before a controller sees them.
 */
class AgenticCallerContextTest {

    private static final UUID BOUND_MERCHANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_MERCHANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID KEY = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @AfterEach
    void clearContext() {
        MerchantContextHolder.clear();
    }

    private AgenticCallerContext context(String demoMerchantId) {
        AgenticProperties properties = new AgenticProperties(
                new AgenticProperties.Platform("http://gateway.test", "sk_test_x", 2000, 10000),
                new AgenticProperties.Policy("2026-08-20.1", "INR", 5_000_000L, 10_000_000L, 100_000L,
                        2_000_000L, 5_000_000L, 60, 30),
                new AgenticProperties.Checkout(30, 20),
                new AgenticProperties.Llm("scripted", "http://llm.test", "", "scripted", 16000, 0.2,
                        30000, 8, 120000, "", ""),
                new AgenticProperties.Razorpay(false, "https://example.invalid", "", "", 2000, 8000,
                        "decline"),
                new AgenticProperties.Demo(demoMerchantId, false));
        return new AgenticCallerContext(properties);
    }

    @Test
    @DisplayName("a verified test-mode session for the bound merchant resolves to that merchant")
    void sessionForBoundMerchant() {
        MerchantContextHolder.set(MerchantContext.forSession(BOUND_MERCHANT, "test", USER,
                Set.of("*"), "m@example.test", null));

        AgenticCallerContext.Caller caller = context(BOUND_MERCHANT.toString()).resolve();

        assertThat(caller.merchantId()).isEqualTo(BOUND_MERCHANT);
        assertThat(caller.mode()).isEqualTo("test");
        assertThat(caller.actor()).isEqualTo("session-user:" + USER);
    }

    @Test
    @DisplayName("an API-key context names the key as the actor")
    void apiKeyActor() {
        MerchantContextHolder.set(MerchantContext.forApiKey(BOUND_MERCHANT, "test", KEY,
                Set.of("*"), "m@example.test", null));

        assertThat(context(BOUND_MERCHANT.toString()).resolve().actor()).isEqualTo("api-key:" + KEY);
    }

    @Test
    @DisplayName("no context at all is refused as unauthorized")
    void noContext() {
        assertThatThrownBy(() -> context(BOUND_MERCHANT.toString()).resolve())
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("a live-mode context is refused — the extension is test-mode only")
    void liveModeRefused() {
        MerchantContextHolder.set(MerchantContext.forSession(BOUND_MERCHANT, "live", USER,
                Set.of("*"), "m@example.test", null));

        assertThatThrownBy(() -> context(BOUND_MERCHANT.toString()).resolve())
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("a session for a merchant this deployment is not bound to is refused")
    void merchantMismatchRefused() {
        MerchantContextHolder.set(MerchantContext.forSession(OTHER_MERCHANT, "test", USER,
                Set.of("*"), "m@example.test", null));

        assertThatThrownBy(() -> context(BOUND_MERCHANT.toString()).resolve())
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("with no demo merchant configured, the context's own merchant is used")
    void noBoundMerchantUsesContext() {
        MerchantContextHolder.set(MerchantContext.forSession(OTHER_MERCHANT, "test", USER,
                Set.of("*"), "m@example.test", null));

        assertThat(context("").resolve().merchantId()).isEqualTo(OTHER_MERCHANT);
    }
}
