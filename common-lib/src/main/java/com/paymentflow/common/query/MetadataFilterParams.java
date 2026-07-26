package com.paymentflow.common.query;

import com.paymentflow.common.exception.BadRequestException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extracts the {@code metadata[key]=value} filter from a request's raw query parameters
 * (M19.8).
 *
 * <p><b>Why this exists rather than a plain {@code @RequestParam Map}.</b> The obvious
 * spelling — {@code @RequestParam(name = "metadata") Map<String, String>} — does not do
 * what it reads like. Spring only binds a {@code Map} to "all request parameters" when
 * the annotation carries <em>no</em> name; naming it sends the parameter down the
 * ordinary single-value path instead, which looks for one parameter literally called
 * {@code metadata}. A request sending {@code metadata[orderId]=A-1} therefore bound
 * nothing at all, the filter reached the query as {@code null}, and the endpoint returned
 * the merchant's entire history <em>as though it had been filtered</em>.
 *
 * <p>That is a filter failing <b>open</b>, on a financial list, and it is the exact
 * failure {@code PaymentListFilter} already refuses to allow for {@code status} — a typo
 * there is a 400 precisely because silently returning the wrong rows is worse than
 * erroring. It shipped because M19's list tests called the repository directly and the
 * gateway's tests answered from a stub, so no test ever sent a query string. Found by
 * M19.8's HTTP-level test; fixed here, in shared code, so the four list endpoints cannot
 * each get it wrong differently.
 *
 * <p>Containment semantics live in the repository ({@code metadata @> :metadata}); this
 * class only decides which parameters are part of the filter.
 */
public final class MetadataFilterParams {

    private static final String PREFIX = "metadata[";
    private static final char SUFFIX = ']';
    private static final String BARE = "metadata";
    private static final String SYNTAX_HELP =
            "metadata filters use the form metadata[key]=value, for example metadata[orderId]=A-1234.";

    private MetadataFilterParams() {
    }

    /**
     * Pulls {@code metadata[key]=value} pairs out of every query parameter on a request.
     *
     * @param requestParams every query parameter, as bound by an unnamed
     *                      {@code @RequestParam Map<String, String>}
     * @return the filter's key/value pairs; empty when no metadata filter was supplied
     * @throws BadRequestException if {@code metadata} was supplied in a shape this API
     *                             does not accept — rejected rather than ignored, because
     *                             an ignored filter returns rows the caller believes were
     *                             excluded
     */
    public static Map<String, String> from(Map<String, String> requestParams) {
        if (requestParams == null || requestParams.isEmpty()) {
            return Map.of();
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : requestParams.entrySet()) {
            String name = entry.getKey();
            if (BARE.equals(name)) {
                // The likeliest way to get the syntax wrong, and the most dangerous to
                // ignore: `?metadata=A-1234` reads like a filter and would otherwise
                // return everything.
                throw new BadRequestException(SYNTAX_HELP);
            }
            if (!name.startsWith(PREFIX)) {
                continue;
            }
            if (name.charAt(name.length() - 1) != SUFFIX) {
                throw new BadRequestException(SYNTAX_HELP);
            }
            String key = name.substring(PREFIX.length(), name.length() - 1);
            if (key.isBlank()) {
                throw new BadRequestException(SYNTAX_HELP);
            }
            metadata.put(key, entry.getValue());
        }
        return metadata;
    }
}
