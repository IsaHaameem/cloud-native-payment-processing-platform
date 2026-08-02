package com.paymentflow.common.security;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The identity rules M23.0 made structural (D185). Each of these would otherwise be a
 * comment that a later change could quietly stop honouring — and a context that misreports
 * who acted is one the audit trail then repeats.
 */
class MerchantContextTest {

    private static final Set<String> SCOPES = Set.of("payments:read");

    @Test
    void anApiKeyContextCarriesAKeyAndNoUser() {
        UUID merchantId = UUID.randomUUID();
        UUID keyId = UUID.randomUUID();

        MerchantContext context = MerchantContext.forApiKey(merchantId, "test", keyId, SCOPES, null, null);

        assertThat(context.principal()).isEqualTo(InternalPrincipal.API_KEY);
        assertThat(context.keyId()).isEqualTo(keyId);
        assertThat(context.userId()).isNull();
    }

    @Test
    void aSessionContextCarriesAUserAndNoKey() {
        UUID merchantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        MerchantContext context = MerchantContext.forSession(merchantId, "live", userId, SCOPES, null, null);

        assertThat(context.principal()).isEqualTo(InternalPrincipal.SESSION);
        assertThat(context.userId()).isEqualTo(userId);
        assertThat(context.keyId()).isNull();
    }

    @Test
    void theSupersededConstructorStillMeansAnApiKey() {
        UUID keyId = UUID.randomUUID();

        MerchantContext context = new MerchantContext(UUID.randomUUID(), "test", keyId, SCOPES, null, null);

        assertThat(context.principal()).isEqualTo(InternalPrincipal.API_KEY);
        assertThat(context.keyId()).isEqualTo(keyId);
    }

    @Test
    void anApiKeyContextWithoutAKeyIsRejected() {
        assertThatThrownBy(() -> new MerchantContext(UUID.randomUUID(), "test", InternalPrincipal.API_KEY,
                null, null, SCOPES, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyId");
    }

    @Test
    void anApiKeyContextWithAUserIsRejected() {
        assertThatThrownBy(() -> new MerchantContext(UUID.randomUUID(), "test", InternalPrincipal.API_KEY,
                UUID.randomUUID(), UUID.randomUUID(), SCOPES, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void aSessionContextWithoutAUserIsRejected() {
        assertThatThrownBy(() -> new MerchantContext(UUID.randomUUID(), "test", InternalPrincipal.SESSION,
                null, null, SCOPES, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void aSessionContextWithAKeyIsRejected() {
        assertThatThrownBy(() -> new MerchantContext(UUID.randomUUID(), "test", InternalPrincipal.SESSION,
                UUID.randomUUID(), UUID.randomUUID(), SCOPES, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyId");
    }

    @Test
    void aContextWithNoPrincipalIsRejected() {
        assertThatThrownBy(() -> new MerchantContext(UUID.randomUUID(), "test", null,
                null, UUID.randomUUID(), SCOPES, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("principal");
    }

    @Test
    void theWildcardScopeStillGrantsEverythingForEitherPrincipal() {
        MerchantContext session = MerchantContext.forSession(UUID.randomUUID(), "test", UUID.randomUUID(),
                Set.of("*"), null, null);

        assertThat(session.hasScope("payments:write")).isTrue();
        assertThat(MerchantContext.forApiKey(UUID.randomUUID(), "test", UUID.randomUUID(), Set.of("*"), null, null)
                .hasScope("logs:read")).isTrue();
    }
}
