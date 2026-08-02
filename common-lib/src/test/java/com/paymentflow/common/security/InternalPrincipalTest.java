package com.paymentflow.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InternalPrincipalTest {

    @Test
    void parsesBothWireValues() {
        assertThat(InternalPrincipal.fromWireValue("api_key")).isEqualTo(InternalPrincipal.API_KEY);
        assertThat(InternalPrincipal.fromWireValue("session")).isEqualTo(InternalPrincipal.SESSION);
    }

    @Test
    void isForgivingAboutCaseAndSurroundingSpace() {
        assertThat(InternalPrincipal.fromWireValue("  SESSION ")).isEqualTo(InternalPrincipal.SESSION);
    }

    @Test
    void treatsAnAbsentOrBlankValueAsAnApiKey() {
        // Every context signed before M23.0 omits the header, and each of them is genuinely
        // an API-key context. This is what makes the change additive rather than a cutover.
        assertThat(InternalPrincipal.fromWireValue(null)).isEqualTo(InternalPrincipal.API_KEY);
        assertThat(InternalPrincipal.fromWireValue("   ")).isEqualTo(InternalPrincipal.API_KEY);
    }

    @Test
    void returnsNullForAnUnknownPrincipalRatherThanGuessing() {
        // Null is the caller's signal to reject. Defaulting an unrecognised principal to
        // API_KEY would silently grant a future credential the privileges of the one this
        // build happens to know about.
        assertThat(InternalPrincipal.fromWireValue("root")).isNull();
        assertThat(InternalPrincipal.fromWireValue("apikey")).isNull();
    }

    @Test
    void everyConstantHasADistinctLowercaseWireValue() {
        assertThat(InternalPrincipal.values())
                .extracting(InternalPrincipal::wireValue)
                .doesNotHaveDuplicates()
                .allSatisfy(value -> assertThat(value).isEqualTo(value.toLowerCase(java.util.Locale.ROOT)));
    }
}
