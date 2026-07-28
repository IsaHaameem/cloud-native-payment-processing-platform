package com.paymentflow.common.error;

import com.paymentflow.common.dto.error.ErrorType;

import java.util.Locale;

/**
 * A machine-readable error classification together with its default HTTP status.
 *
 * <p>Services may define their own domain-specific {@code ErrorCode} implementations
 * (e.g. {@code PAYMENT_ALREADY_CAPTURED}) while reusing the generic ones in
 * {@link CommonErrorCode}. Keeping this an interface avoids forcing every error code
 * into a single shared enum, which would couple all services together.
 *
 * <p><b>M21.4 added {@link #type()} and {@link #docUrl()}</b> (§5/M21 task 3), and that is
 * what makes this interface the single source of truth the milestone asks for rather than
 * half of one. Before, a code knew its own status and message but nothing about how it
 * should be classified or where it is documented, so those two facts would have had to live
 * in a table maintained beside it — and a table maintained beside a thing is a table that
 * eventually disagrees with it. Declaring {@code type()} on the interface makes the compiler
 * the reviewer: a new code cannot be added without classifying it.
 */
public interface ErrorCode {

    /** Stable, machine-readable identifier (e.g. {@code NOT_FOUND}). */
    String code();

    /** Default HTTP status associated with this error. */
    int httpStatus();

    /** Human-readable default message; individual throw sites may override it. */
    String defaultMessage();

    /**
     * The coarse classification this code belongs to — the small, stable half of the
     * contract, and the half §7.1's SDKs map onto their typed exception hierarchy.
     */
    ErrorType type();

    /**
     * Where a developer reads about this code. Derived rather than declared per code, so a
     * code cannot ship with a link that points nowhere or, worse, at a different code's
     * section.
     *
     * <p>One page with an anchor per code (§9.1's {@code /errors}) rather than a page each,
     * because these are most useful read together: "which of these can I retry?" is the
     * question integrators actually arrive with.
     */
    default String docUrl() {
        return ErrorCatalogue.DOC_BASE_URL + "#" + code().toLowerCase(Locale.ROOT);
    }
}
