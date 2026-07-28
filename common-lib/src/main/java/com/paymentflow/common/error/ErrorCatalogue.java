package com.paymentflow.common.error;

import java.util.Comparator;
import java.util.List;

/**
 * The error-code catalogue: the one enumeration of every code the public API can return
 * (M21.4, §5/M21 task 3 — "document every code in one table that is the source of truth for
 * both the docs site and the SDKs").
 *
 * <p><b>Why this exists when {@link CommonErrorCode} is already an enum.</b> Because
 * {@link ErrorCode} is deliberately an interface: services are free to declare their own
 * domain codes, and that freedom is the whole reason the catalogue cannot be "just the
 * enum". What the docs site (M25), the SDK generators (M22) and {@code docs/ERRORS.md} need
 * is a list of everything a caller can receive, assembled from wherever the codes live.
 * Today every published code is a {@code CommonErrorCode}; when a service adds a domain
 * code, it registers it here and the consistency test fails until the documentation names
 * it too.
 *
 * <p>The registration being manual is the point. An automatic classpath scan would be less
 * work and would silently publish any code a service happened to declare — including ones
 * only ever thrown on an internal path, which the public catalogue should not promise.
 * Appearing here is a statement that a code is part of the public contract.
 */
public final class ErrorCatalogue {

    /** The published catalogue page (§9.1). M25 renders it; the anchor is the code, lowercased. */
    public static final String DOC_BASE_URL = "https://docs.paymentflow.dev/errors";

    private ErrorCatalogue() {
    }

    /**
     * Every error code the public {@code /v1} tier can return, ordered by HTTP status and
     * then by code, which is the order {@code docs/ERRORS.md} lists them in and the order a
     * reader scanning for "what does a 409 mean here?" wants.
     */
    public static List<ErrorCode> published() {
        return List.of(CommonErrorCode.values()).stream()
                .sorted(Comparator.comparingInt(ErrorCode::httpStatus).thenComparing(ErrorCode::code))
                .map(ErrorCode.class::cast)
                .toList();
    }
}
