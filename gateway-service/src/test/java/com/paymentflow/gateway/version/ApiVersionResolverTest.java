package com.paymentflow.gateway.version;

import com.paymentflow.common.dto.version.ApiVersions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The precedence rule that decides which revision answers a request (M21.5).
 *
 * <p>§5/M21's testing strategy names "version parsing and resolution precedence" explicitly,
 * and precedence is the part with a real choice in it: header over pin, pin over default,
 * and an unknown version as an error rather than a silent fallback.
 */
class ApiVersionResolverTest {

    private final ApiVersionResolver resolver = new ApiVersionResolver();

    @Test
    void withNeitherHeaderNorPinTheCurrentRevisionAnswers() {
        // An unauthenticated caller — GET /v1/test/cards — and a merchant whose pin has not
        // been written yet.
        assertThat(resolver.resolve(null, null)).isEqualTo(ApiVersions.CURRENT);
    }

    @Test
    void theMerchantPinIsUsedWhenThereIsNoHeader() {
        assertThat(resolver.resolve(null, "2026-07-27")).isEqualTo(ApiVersions.V2026_07_27);
    }

    @Test
    void theHeaderOverridesThePin() {
        // The precedence that makes upgrading a one-line experiment rather than a support
        // ticket: a merchant pinned to the old revision can try the new one per request.
        assertThat(resolver.resolve("2026-08-01", "2026-07-27")).isEqualTo(ApiVersions.V2026_08_01);
        // ...and in the other direction, so an integrator can reproduce a bug report against
        // the shape their production traffic still receives.
        assertThat(resolver.resolve("2026-07-27", "2026-08-01")).isEqualTo(ApiVersions.V2026_07_27);
    }

    @Test
    void aBlankHeaderIsTreatedAsAbsentRatherThanInvalid() {
        // Some HTTP clients send an empty header rather than omitting it; failing those
        // requests would be a hostile reading of a request that clearly means "no preference".
        assertThat(resolver.resolve("", "2026-07-27")).isEqualTo(ApiVersions.V2026_07_27);
        assertThat(resolver.resolve("   ", null)).isEqualTo(ApiVersions.CURRENT);
    }

    @Test
    void anUnknownVersionInTheHeaderIsAnErrorRatherThanAFallback() {
        // Silently answering in a different revision than the one asked for would hand the
        // caller a shape they did not request with no way to notice. The message lists what
        // is actually available, because "unsupported" alone leaves them guessing.
        assertThatThrownBy(() -> resolver.resolve("2027-01-01", null))
                .isInstanceOf(UnsupportedApiVersionException.class)
                .hasMessageContaining("2027-01-01")
                .hasMessageContaining("2026-08-01");
    }

    @Test
    void aMalformedVersionInTheHeaderIsTheSameError() {
        assertThatThrownBy(() -> resolver.resolve("latest", null))
                .isInstanceOf(UnsupportedApiVersionException.class)
                .satisfies(e -> assertThat(((UnsupportedApiVersionException) e).requested()).isEqualTo("latest"));
    }

    @Test
    void anUnsupportedStoredPinFallsForwardInsteadOfFailingTheRequest() {
        // The asymmetry with the header case, and it is deliberate. A stored pin naming a
        // sunset revision is a platform-side situation the merchant did not cause; failing
        // every one of their requests would be the worst possible way to inform them. A
        // header naming the same version is a client bug, and is rejected.
        assertThat(resolver.resolve(null, "2020-01-01")).isEqualTo(ApiVersions.CURRENT);
    }

    @Test
    void aMalformedStoredPinFallsForwardRatherThanThrowing() {
        // The database constraint makes this unreachable today. Handled anyway, because the
        // alternative is that a bad row takes a merchant's entire integration offline.
        assertThat(resolver.resolve(null, "not-a-date")).isEqualTo(ApiVersions.CURRENT);
        assertThat(resolver.resolve(null, "")).isEqualTo(ApiVersions.CURRENT);
    }
}
