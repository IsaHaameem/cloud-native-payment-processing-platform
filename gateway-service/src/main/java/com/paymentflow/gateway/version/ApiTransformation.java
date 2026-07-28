package com.paymentflow.gateway.version;

import com.paymentflow.common.dto.version.ApiVersion;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

/**
 * One revision boundary, expressed as a pair of translations (M21.5, §4.10).
 *
 * <p>A transformation belongs to the revision it is the <em>upper edge</em> of. The
 * {@link #appliesFrom()} version is the revision that introduced the change, so a caller
 * pinned strictly <em>before</em> it must have their traffic translated: their request
 * rewritten forward into the shape the services now speak, and the response rewritten back
 * into the shape their revision promised.
 *
 * <p><b>The direction that is easy to get backwards.</b> Services always speak the current
 * revision — nothing downstream of the gateway knows versions exist, which is the whole point
 * of putting this at the edge. So:
 * <ul>
 *   <li>{@link #transformRequest} converts <em>old → current</em>. The caller sent the old
 *       shape; the service must receive the new one.</li>
 *   <li>{@link #transformResponse} converts <em>current → old</em>. The service produced the
 *       new shape; the caller must receive the one they pinned.</li>
 * </ul>
 *
 * <p><b>Why an interface with a registry rather than a filter per revision.</b> The approved
 * decision for M21 was explicit that the transformation layer stays generic and
 * registry-driven, with no per-endpoint special cases. That constraint is what keeps the
 * cost of a revision proportional to the change rather than to the size of the API: this
 * interface sees a path, a query map and a JSON body, and knows nothing about which
 * controller produced them. A transformation that needed to ask "is this the capture
 * endpoint?" would be a sign the revision was too broad to be one revision.
 */
public interface ApiTransformation {

    /**
     * The revision that introduced the change. Callers pinned strictly before this are
     * transformed; callers at or after it are passed through untouched.
     */
    ApiVersion appliesFrom();

    /** A one-line description, used in logs and in the deprecation documentation. */
    String description();

    /**
     * Rewrites an inbound request from the older revision's shape into the current one.
     *
     * @param path        the request path, for transformations that are resource-scoped
     * @param queryParams the inbound query parameters; returned unchanged if nothing applies
     * @return the parameters the service should see
     */
    default MultiValueMap<String, String> transformRequestParams(String path,
                                                                 MultiValueMap<String, String> queryParams) {
        return queryParams;
    }

    /**
     * Rewrites an inbound request body from the older revision's shape into the current one.
     *
     * @param body mutated in place or replaced; returning the argument unchanged is normal
     */
    default JsonNode transformRequestBody(String path, JsonNode body) {
        return body;
    }

    /**
     * Rewrites an outbound response body from the current revision's shape back into the
     * older one.
     *
     * <p>This is the half that carries the compatibility promise: a caller pinned to an old
     * revision must not be able to tell that the platform moved on.
     */
    JsonNode transformResponseBody(String path, JsonNode body);

    /**
     * Rewrites outbound response headers. Rarely needed — the default is to leave them
     * alone — but a revision that renamed a header would have nowhere else to do it.
     */
    default void transformResponseHeaders(String path, HttpHeaders headers) {
        // no-op
    }
}
