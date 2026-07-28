package com.paymentflow.common.dto.version;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * One dated revision of the public API contract (M21.5, §4.10).
 *
 * <p>The URL stays {@code /v1/} permanently — {@code v1} names the API <em>family</em>, and
 * this names the <em>revision</em>. A {@code /v2/} path would only ever appear for a total
 * redesign, and this type is what makes that unnecessary: an integrator pins a date, the
 * platform keeps serving them the shape that date described, and improvements ship
 * continuously to everyone who has not pinned.
 *
 * <p><b>Why a type rather than a {@code String}.</b> Every question the versioning layer asks
 * is an ordering question — is this request older than the revision that changed this field?
 * — and string comparison answers it correctly for ISO dates only by accident of encoding.
 * Parsing also has to happen somewhere: {@code PaymentFlow-Version: yesterday} is a client
 * error that must produce a catalogued 400 rather than an exception in a filter.
 *
 * <p>Ordering is by date, so {@link Comparable} means what it looks like it means: an earlier
 * revision is "less than" a later one.
 */
public record ApiVersion(LocalDate date) implements Comparable<ApiVersion> {

    public ApiVersion {
        Objects.requireNonNull(date, "date");
    }

    /**
     * Parses the wire form, {@code yyyy-MM-dd}.
     *
     * @throws IllegalArgumentException if the value is not an ISO date. Callers at the edge
     *                                  translate this into a catalogued
     *                                  {@code UNSUPPORTED_API_VERSION}; it is deliberately
     *                                  unchecked because every internal construction site
     *                                  passes a literal.
     */
    public static ApiVersion parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An API version must be a date in yyyy-MM-dd form.");
        }
        try {
            return new ApiVersion(LocalDate.parse(value.trim()));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "'" + value + "' is not a valid API version. Versions are dates, in yyyy-MM-dd form.", e);
        }
    }

    /** True when this revision is the same as or newer than {@code other}. */
    public boolean isAtLeast(ApiVersion other) {
        return compareTo(other) >= 0;
    }

    /** True when this revision is strictly older than {@code other}. */
    public boolean isBefore(ApiVersion other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(ApiVersion other) {
        return date.compareTo(other.date);
    }

    /** The wire form. Also what {@code info.version} carries in the OpenAPI document. */
    @Override
    public String toString() {
        return date.toString();
    }
}
