package com.paymentflow.gateway.version;

import com.paymentflow.common.dto.version.ApiVersion;
import com.paymentflow.common.dto.version.ApiVersions;
import org.springframework.stereotype.Component;

/**
 * Decides which revision of the contract a request is answered in (M21.5, §4.10).
 *
 * <p>The precedence is fixed and is the whole of the resolution rule:
 * <ol>
 *   <li>the {@code PaymentFlow-Version} request header, if present — a per-request
 *       override, which is what lets an integrator test a new revision against one call
 *       before repinning;</li>
 *   <li>the merchant's pinned version, set on their first call and carried on the API-key
 *       verification response the gateway already resolves and caches;</li>
 *   <li>{@link ApiVersions#CURRENT}, for a caller with neither — an unauthenticated
 *       endpoint, or a merchant whose pin has not yet been written.</li>
 * </ol>
 *
 * <p><b>Why the header wins over the pin.</b> The opposite order would make the pin a cage:
 * a merchant could never try the new shape without an out-of-band change to their account,
 * and the migration path for every future revision would run through support. Letting the
 * header win makes the upgrade a one-line experiment, and it cannot surprise anyone, because
 * nothing sends the header by accident.
 *
 * <p><b>An unknown version is an error, not a fallback.</b> Silently serving the current
 * revision to a caller who asked for {@code 2027-01-01} would give them a response whose
 * shape they did not ask for and no way to notice. §4.10's promise is that a pinned client
 * keeps working; a client asking for a version that does not exist has a bug, and the
 * platform says so.
 */
@Component
public class ApiVersionResolver {

    /**
     * Resolves the effective revision.
     *
     * @param requestedHeader the raw {@code PaymentFlow-Version} header, or {@code null}
     * @param merchantPin     the merchant's pinned revision in wire form, or {@code null}
     * @throws UnsupportedApiVersionException if the header names a version this platform
     *                                        does not serve — including one that is
     *                                        well-formed but unknown, and one that is not a
     *                                        date at all
     */
    public ApiVersion resolve(String requestedHeader, String merchantPin) {
        if (requestedHeader != null && !requestedHeader.isBlank()) {
            return validated(requestedHeader);
        }
        if (merchantPin != null && !merchantPin.isBlank()) {
            // Not validated against SUPPORTED: a stored pin that has since been sunset is a
            // platform-side situation, not a client error, and failing the merchant's
            // requests would be the worst possible way to tell them. Falling forward to the
            // current revision is the documented sunset behaviour (§4.10).
            try {
                ApiVersion pinned = ApiVersion.parse(merchantPin);
                return ApiVersions.isSupported(pinned) ? pinned : ApiVersions.CURRENT;
            } catch (IllegalArgumentException e) {
                return ApiVersions.CURRENT;
            }
        }
        return ApiVersions.CURRENT;
    }

    private ApiVersion validated(String requested) {
        ApiVersion version;
        try {
            version = ApiVersion.parse(requested);
        } catch (IllegalArgumentException e) {
            throw new UnsupportedApiVersionException(requested, e.getMessage());
        }
        if (!ApiVersions.isSupported(version)) {
            throw new UnsupportedApiVersionException(requested,
                    "'" + requested + "' is not a supported API version. Supported versions are "
                            + String.join(", ", ApiVersions.supportedWireForms()) + ".");
        }
        return version;
    }
}
