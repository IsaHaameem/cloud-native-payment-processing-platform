package com.paymentflow.gateway.version;

import com.paymentflow.common.dto.version.ApiVersion;
import com.paymentflow.common.dto.version.ApiVersions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chain that turns a pinned revision into an ordered list of transformations (M21.5).
 *
 * <p><b>Why most of this is tested against synthetic revisions.</b> V2 has exactly one
 * superseded revision, and with one the two orderings — oldest-first for requests,
 * newest-first for responses — are indistinguishable: a single-element list is sorted either
 * way. A test written only against the real registry would therefore pass whichever way the
 * comparator pointed, and the milestone that adds a second revision would inherit a silently
 * wrong composition order. Three synthetic revisions make the ordering observable now.
 */
class ApiTransformationRegistryTest {

    private static final ApiVersion V1 = ApiVersion.parse("2026-01-01");
    private static final ApiVersion V2 = ApiVersion.parse("2026-02-01");
    private static final ApiVersion V3 = ApiVersion.parse("2026-03-01");

    /** A transformation that only records which revision it belongs to. */
    private record NamedTransformation(ApiVersion appliesFrom) implements ApiTransformation {
        @Override
        public String description() {
            return "test transformation from " + appliesFrom;
        }

        @Override
        public JsonNode transformResponseBody(String path, JsonNode body) {
            return body;
        }
    }

    private static ApiTransformationRegistry registryOf(ApiVersion... versions) {
        return new ApiTransformationRegistry(
                List.of(versions).stream().map(NamedTransformation::new).map(ApiTransformation.class::cast).toList());
    }

    @Test
    void aCallerOnTheCurrentRevisionNeedsNoTransformations() {
        // The path almost every request takes. If this returned anything, every response
        // would be buffered and reparsed for nothing.
        ApiTransformationRegistry registry = registryOf(V2, V3);

        assertThat(registry.forRequest(V3)).isEmpty();
        assertThat(registry.forResponse(V3)).isEmpty();
        assertThat(registry.isCurrent(V3)).isTrue();
    }

    @Test
    void aTransformationAppliesOnlyToCallersPinnedStrictlyBeforeIt() {
        ApiTransformationRegistry registry = registryOf(V2);

        // Pinned before the change: needs it. Pinned at or after: already speaks that shape.
        assertThat(registry.forRequest(V1)).hasSize(1);
        assertThat(registry.forRequest(V2)).isEmpty();
        assertThat(registry.forRequest(V3)).isEmpty();
    }

    @Test
    void requestsAreTransformedOldestRevisionFirst() {
        // A request arrives in the caller's old shape and must be walked *forward* through
        // each boundary in turn until it is the shape the services speak.
        ApiTransformationRegistry registry = registryOf(V3, V2);

        assertThat(registry.forRequest(V1).stream().map(ApiTransformation::appliesFrom))
                .containsExactly(V2, V3);
    }

    @Test
    void responsesAreTransformedNewestRevisionFirst() {
        // A response leaves the service in the current shape and must be walked *backward*
        // to the caller's. Applying these in request order would run the oldest boundary's
        // rewrite against a body that has not yet been stepped down to its input shape.
        ApiTransformationRegistry registry = registryOf(V2, V3);

        assertThat(registry.forResponse(V1).stream().map(ApiTransformation::appliesFrom))
                .containsExactly(V3, V2);
    }

    @Test
    void theTwoDirectionsAreExactReversesOfEachOther() {
        // The property that makes a round trip lossless: whatever the request chain did on
        // the way in, the response chain undoes in the opposite order on the way out.
        ApiTransformationRegistry registry = registryOf(V3, V1, V2);

        List<ApiVersion> request = registry.forRequest(ApiVersion.parse("2025-12-01"))
                .stream().map(ApiTransformation::appliesFrom).toList();
        List<ApiVersion> response = registry.forResponse(ApiVersion.parse("2025-12-01"))
                .stream().map(ApiTransformation::appliesFrom).toList();

        assertThat(response).containsExactlyElementsOf(request.reversed());
    }

    @Test
    void registrationOrderDoesNotAffectTheResult() {
        // Transformations are Spring beans, and bean ordering is not something to rely on.
        assertThat(registryOf(V3, V2, V1).all().stream().map(ApiTransformation::appliesFrom))
                .containsExactly(V1, V2, V3);
    }

    @Test
    void anEmptyRegistryIsValidAndTransformsNothing() {
        // The state the platform is in before its first revision, and the state it returns
        // to if a revision is ever sunset. Neither should need special handling.
        ApiTransformationRegistry registry = new ApiTransformationRegistry(List.of());

        assertThat(registry.forRequest(V1)).isEmpty();
        assertThat(registry.isCurrent(V1)).isTrue();
    }

    @Test
    void theRealRegistryTransformsTheSupersededRevisionAndNotTheCurrentOne() {
        // The one assertion that is about the platform rather than the mechanism.
        ApiTransformationRegistry registry =
                new ApiTransformationRegistry(List.of(new StatusCaseTransformation()));

        assertThat(registry.forResponse(ApiVersions.V2026_07_27)).hasSize(1);
        assertThat(registry.forResponse(ApiVersions.CURRENT)).isEmpty();
    }
}
