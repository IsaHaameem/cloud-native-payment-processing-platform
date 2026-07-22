package com.paymentflow.gateway.security.apikey;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyFormatTest {

    @Test
    void classifiesASecretKey() {
        assertThat(ApiKeyFormat.classify("sk_test_abc123")).isEqualTo(ApiKeyFormat.CredentialType.API_KEY);
    }

    @Test
    void classifiesAPublishableKey() {
        assertThat(ApiKeyFormat.classify("pk_live_abc123")).isEqualTo(ApiKeyFormat.CredentialType.API_KEY);
    }

    @Test
    void classifiesAThreeSegmentJwt() {
        assertThat(ApiKeyFormat.classify("header.payload.signature")).isEqualTo(ApiKeyFormat.CredentialType.JWT);
    }

    @Test
    void classifiesNullAndBlankAsUnknown() {
        assertThat(ApiKeyFormat.classify(null)).isEqualTo(ApiKeyFormat.CredentialType.UNKNOWN);
        assertThat(ApiKeyFormat.classify("")).isEqualTo(ApiKeyFormat.CredentialType.UNKNOWN);
        assertThat(ApiKeyFormat.classify("   ")).isEqualTo(ApiKeyFormat.CredentialType.UNKNOWN);
    }

    @Test
    void classifiesSomethingThatIsNeitherAsUnknown() {
        assertThat(ApiKeyFormat.classify("not-a-credential-at-all")).isEqualTo(ApiKeyFormat.CredentialType.UNKNOWN);
    }

    @Test
    void isPublishableOnlyForPkPrefix() {
        assertThat(ApiKeyFormat.isPublishable("pk_test_abc")).isTrue();
        assertThat(ApiKeyFormat.isPublishable("sk_test_abc")).isFalse();
        assertThat(ApiKeyFormat.isPublishable(null)).isFalse();
    }

    @Test
    void sha256HexIsDeterministicAndDiffersForDifferentInput() {
        assertThat(ApiKeyFormat.sha256Hex("sk_test_abc")).isEqualTo(ApiKeyFormat.sha256Hex("sk_test_abc"));
        assertThat(ApiKeyFormat.sha256Hex("sk_test_abc")).isNotEqualTo(ApiKeyFormat.sha256Hex("sk_test_xyz"));
    }
}
