package com.paymentflow.gateway.version;

import com.paymentflow.common.dto.version.ApiVersion;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * The ordered chain of transformations between a pinned revision and the current one
 * (M21.5).
 *
 * <p>Every {@link ApiTransformation} bean registers itself here by being a bean — adding a
 * revision means adding one class, not editing a list, which is what "registry-driven" is
 * for. The registry's only job is to answer, for a given pinned version, which
 * transformations apply and in what order.
 *
 * <p><b>The ordering is the part worth being careful about.</b> Transformations compose:
 * with revisions A &lt; B &lt; C and a caller pinned at A, a response leaves the service in
 * C's shape and must be walked back C→B then B→A. Requests go the other way, A→B then B→C.
 * So the two directions iterate the same list in opposite orders, and getting that wrong
 * produces a system that is correct with exactly one superseded revision — which is all V2
 * ever has — and silently wrong the moment a second is added. The order is therefore
 * explicit here and asserted by tests using synthetic revisions, rather than left to be
 * discovered by the milestone that adds the second one.
 */
@Component
public class ApiTransformationRegistry {

    /** Ascending by the revision each one introduces. */
    private final List<ApiTransformation> transformations;

    public ApiTransformationRegistry(List<ApiTransformation> transformations) {
        this.transformations = transformations.stream()
                .sorted(Comparator.comparing(ApiTransformation::appliesFrom))
                .toList();
    }

    /**
     * The transformations a caller pinned at {@code pinned} needs, in the order to apply
     * them to an <em>outbound response</em> — newest revision first, walking the body back
     * through each boundary until it reaches the caller's shape.
     *
     * <p>A transformation applies when the caller is pinned strictly before the revision
     * that introduced it. A caller at or after it already speaks that shape.
     */
    public List<ApiTransformation> forResponse(ApiVersion pinned) {
        return transformations.stream()
                .filter(transformation -> pinned.isBefore(transformation.appliesFrom()))
                .sorted(Comparator.comparing(ApiTransformation::appliesFrom).reversed())
                .toList();
    }

    /**
     * The same set, in the order to apply them to an <em>inbound request</em> — oldest
     * first, walking the caller's shape forward until it is the one the services speak.
     */
    public List<ApiTransformation> forRequest(ApiVersion pinned) {
        return transformations.stream()
                .filter(transformation -> pinned.isBefore(transformation.appliesFrom()))
                .toList();
    }

    /** True when a caller at this revision needs no translation at all — the common case. */
    public boolean isCurrent(ApiVersion pinned) {
        return forRequest(pinned).isEmpty();
    }

    /** Every registered transformation, ascending. For diagnostics and documentation. */
    public List<ApiTransformation> all() {
        return transformations;
    }
}
