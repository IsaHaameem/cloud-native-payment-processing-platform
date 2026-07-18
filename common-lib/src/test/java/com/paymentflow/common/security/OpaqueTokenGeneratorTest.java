package com.paymentflow.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpaqueTokenGeneratorTest {

    @Test
    void generatesDistinctUrlSafeSecretsOfExpectedLength() {
        String first = OpaqueTokenGenerator.generate();
        String second = OpaqueTokenGenerator.generate();

        assertThat(first).isNotEqualTo(second);
        assertThat(first).doesNotContain("+", "/", "=");
        // 32 bytes, unpadded URL-safe Base64: ceil(32 * 4 / 3) = 43 chars.
        assertThat(first).hasSize(43);
    }

    @Test
    void hashIsDeterministicAndHexEncoded() {
        String hash = OpaqueTokenGenerator.sha256Hex("some-raw-secret");

        assertThat(hash).isEqualTo(OpaqueTokenGenerator.sha256Hex("some-raw-secret"));
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    void differentInputsHashDifferently() {
        assertThat(OpaqueTokenGenerator.sha256Hex("a")).isNotEqualTo(OpaqueTokenGenerator.sha256Hex("b"));
    }
}
