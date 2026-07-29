package com.paymentflow.openapi;

/**
 * One difference between two revisions of the published OpenAPI document, and what it means
 * for a client that was written against the older one (M21.6, §5/M21 task 5).
 *
 * <p>The classification is the whole point of the gate. §15's backward-compatibility rule
 * says additive changes — new endpoints, new fields, new enum values — ship unversioned,
 * while anything a correct client could notice as a removal or a change of meaning needs a
 * new dated revision. A diff that only reported "these two documents differ" would force a
 * human to re-derive that judgement on every pull request, which is exactly the review step
 * that stops happening once the document is 3,600 lines long.
 */
public record OpenApiChange(Classification classification, String location, String detail)
        implements Comparable<OpenApiChange> {

    /** What a change does to a client written against the previous document. */
    public enum Classification {
        /**
         * Safe to ship without a new revision: the previous contract is still honoured in
         * full, and the document merely says more than it did.
         */
        ADDITIVE,

        /**
         * Not safe to ship without a new dated revision and a transformation for the
         * previous one. Something the old document promised is gone, narrowed, or now
         * means something else.
         */
        BREAKING
    }

    public static OpenApiChange additive(String location, String detail) {
        return new OpenApiChange(Classification.ADDITIVE, location, detail);
    }

    public static OpenApiChange breaking(String location, String detail) {
        return new OpenApiChange(Classification.BREAKING, location, detail);
    }

    public boolean isBreaking() {
        return classification == Classification.BREAKING;
    }

    /**
     * Breaking changes first, then by location — so the report's first screen is the half a
     * developer has to act on, whatever else the diff found.
     */
    @Override
    public int compareTo(OpenApiChange other) {
        // Reversed deliberately: BREAKING sorts *before* ADDITIVE, which is the opposite of
        // their declaration order. The enum is declared additive-first because that is the
        // order the two are explained in; the report is ordered breaking-first because that
        // is the half a developer has to act on.
        int byClassification = other.classification.compareTo(classification);
        return byClassification != 0 ? byClassification : location.compareTo(other.location);
    }

    @Override
    public String toString() {
        return "%-9s %s%n            %s".formatted(classification, location, detail);
    }
}
