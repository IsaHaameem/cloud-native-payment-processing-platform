package com.paymentflow.common.dto.version;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Every revision of the public API this platform currently serves (M21.5, §4.10).
 *
 * <p>This is the single place the set of supported revisions is written down. The OpenAPI
 * document's {@code info.version} reads {@link #CURRENT} from here, the gateway validates
 * the {@code PaymentFlow-Version} header against {@link #SUPPORTED} from here, and the
 * transformation registry is keyed on the same constants — so "which versions exist?" has
 * one answer rather than three that agree until they do not.
 *
 * <p><b>The count is capped by policy, not by capability</b> (§5/M21's risk table). Every
 * supported revision beyond the current one costs a transformation that has to be correct
 * forever and tested on every build. V2 supports exactly one superseded revision at a time;
 * when a third is added, the oldest is sunset first. That is a deliberate constraint, and
 * {@link #SUPERSEDED} having one element is the enforcement of it being visible rather than
 * assumed.
 */
public final class ApiVersions {

    /**
     * The first published revision — the shape M21.1–M21.4 described and the one every
     * integrator who called the platform before {@link #CURRENT} is pinned to.
     */
    public static final ApiVersion V2026_07_27 = ApiVersion.parse("2026-07-27");

    /**
     * The current revision. Payment and refund {@code status} values are lowercase
     * {@code snake_case} on the wire here, where {@link #V2026_07_27} spelled them
     * {@code SCREAMING_SNAKE_CASE}.
     */
    public static final ApiVersion V2026_08_01 = ApiVersion.parse("2026-08-01");

    /** What an unpinned caller gets, and what a new merchant is pinned to on first call. */
    public static final ApiVersion CURRENT = V2026_08_01;

    /** Oldest first. The order matters: transformations are applied newest-to-oldest. */
    public static final List<ApiVersion> SUPPORTED = List.of(V2026_07_27, V2026_08_01);

    /**
     * The revisions that are no longer current and are therefore served through a
     * transformation. Every one of these gets {@code Deprecation} and {@code Sunset}
     * response headers.
     */
    public static final List<ApiVersion> SUPERSEDED = List.of(V2026_07_27);

    /**
     * When {@link #V2026_07_27} stops being served.
     *
     * <p>Twelve months from the day it was superseded, which is the timeline §4.10 promises
     * and long enough that an integrator who checks in twice a year still sees the warning
     * twice. Stated as a constant rather than computed from "now", because a sunset date
     * that moved every time the service restarted would be worthless to plan against — and
     * because the {@code Sunset} header is a promise, not a status.
     */
    public static final LocalDate V2026_07_27_SUNSET = LocalDate.parse("2027-08-01");

    private ApiVersions() {
    }

    /** True when the platform still serves this revision. */
    public static boolean isSupported(ApiVersion version) {
        return SUPPORTED.contains(version);
    }

    /** True when this revision is served through a transformation rather than natively. */
    public static boolean isSuperseded(ApiVersion version) {
        return SUPERSEDED.contains(version);
    }

    /** The sunset date for a superseded revision, or empty for the current one. */
    public static Optional<LocalDate> sunsetOf(ApiVersion version) {
        return V2026_07_27.equals(version) ? Optional.of(V2026_07_27_SUNSET) : Optional.empty();
    }

    /** The supported revisions in wire form, for error messages that have to list them. */
    public static List<String> supportedWireForms() {
        return SUPPORTED.stream().map(ApiVersion::toString).toList();
    }
}
