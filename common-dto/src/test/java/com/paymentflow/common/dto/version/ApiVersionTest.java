package com.paymentflow.common.dto.version;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dated revision type and the registry of revisions the platform serves (M21.5).
 *
 * <p>Parsing and ordering are the whole of this type's behaviour, and both are load-bearing:
 * the gateway turns a header into one of these on every request, and every question the
 * transformation registry asks is an ordering question.
 */
class ApiVersionTest {

    @Test
    void theWireFormRoundTrips() {
        assertThat(ApiVersion.parse("2026-08-01")).hasToString("2026-08-01");
    }

    @Test
    void surroundingWhitespaceIsTolerated() {
        // Header values arrive with whatever a client's HTTP library left on them.
        assertThat(ApiVersion.parse("  2026-08-01 ")).isEqualTo(ApiVersions.V2026_08_01);
    }

    @Test
    void anythingThatIsNotAnIsoDateIsRejectedWithAMessageThatSaysTheShape() {
        // The message is the whole remedy: a caller sending "v1" or "latest" needs to be
        // told what a version looks like, not merely that theirs was wrong.
        assertThatThrownBy(() -> ApiVersion.parse("latest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");
        assertThatThrownBy(() -> ApiVersion.parse("2026-13-45"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiVersion.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiVersion.parse("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void orderingIsByDateRatherThanByString() {
        assertThat(ApiVersions.V2026_07_27).isLessThan(ApiVersions.V2026_08_01);
        assertThat(ApiVersions.V2026_07_27.isBefore(ApiVersions.V2026_08_01)).isTrue();
        assertThat(ApiVersions.V2026_08_01.isAtLeast(ApiVersions.V2026_07_27)).isTrue();
        assertThat(ApiVersions.V2026_08_01.isAtLeast(ApiVersions.V2026_08_01)).isTrue();
        assertThat(ApiVersions.V2026_08_01.isBefore(ApiVersions.V2026_07_27)).isFalse();
    }

    @Test
    void twoVersionsWithTheSameDateAreEqual() {
        // The registry filters with `contains`, so value equality is load-bearing rather
        // than incidental.
        assertThat(ApiVersion.parse("2026-08-01")).isEqualTo(ApiVersions.V2026_08_01);
    }

    @Test
    void theCurrentRevisionIsTheNewestSupportedOne() {
        // A CURRENT that was not the newest would make every unpinned caller a candidate for
        // transformation, which is the one path that must never need one.
        assertThat(ApiVersions.SUPPORTED).isSortedAccordingTo(ApiVersion::compareTo);
        assertThat(ApiVersions.CURRENT).isEqualTo(ApiVersions.SUPPORTED.getLast());
    }

    @Test
    void everySupersededRevisionIsAlsoSupported() {
        // Superseded means "served through a transformation", not "no longer served". A
        // revision in one list and not the other would either be silently unreachable or
        // silently untransformed.
        assertThat(ApiVersions.SUPPORTED).containsAll(ApiVersions.SUPERSEDED);
        assertThat(ApiVersions.SUPERSEDED).doesNotContain(ApiVersions.CURRENT);
    }

    @Test
    void exactlyOneRevisionIsSupersededAtATime() {
        // §5/M21's risk table caps the number of concurrently supported revisions by policy,
        // because each one is a transformation that must stay correct forever. Asserted so
        // adding a second is a deliberate act that updates this test, not a drift.
        assertThat(ApiVersions.SUPERSEDED).hasSize(1);
    }

    @Test
    void everySupersededRevisionHasASunsetDateAndTheCurrentOneDoesNot() {
        assertThat(ApiVersions.sunsetOf(ApiVersions.CURRENT)).isEmpty();
        ApiVersions.SUPERSEDED.forEach(version ->
                assertThat(ApiVersions.sunsetOf(version))
                        .describedAs("%s is served through a transformation but promises no sunset", version)
                        .isPresent());
    }

    @Test
    void theSunsetIsAfterTheRevisionItRetires() {
        assertThat(ApiVersions.V2026_07_27_SUNSET).isAfter(ApiVersions.V2026_08_01.date());
    }

    @Test
    void supportIsCheckedByValueNotByIdentity() {
        assertThat(ApiVersions.isSupported(ApiVersion.parse("2026-07-27"))).isTrue();
        assertThat(ApiVersions.isSupported(ApiVersion.parse("2025-01-01"))).isFalse();
        assertThat(ApiVersions.isSuperseded(ApiVersion.parse("2026-07-27"))).isTrue();
        assertThat(ApiVersions.isSuperseded(ApiVersions.CURRENT)).isFalse();
    }
}
