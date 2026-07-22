package com.paymentflow.payment.mode;

import com.paymentflow.common.exception.BadRequestException;
import com.paymentflow.common.security.MerchantContext;
import com.paymentflow.common.security.MerchantContextHolder;
import com.paymentflow.payment.domain.Mode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Resolves the test/live mode of the current request (M16, §4.4) as its canonical
 * lowercase value ({@code "test"}/{@code "live"}). One rule, applied on the servlet
 * request thread before any bulkhead hand-off (so both the request-scoped
 * {@link MerchantContextHolder} and the {@link HttpServletRequest} are visible):
 *
 * <ol>
 *   <li><b>API-key path</b> — the gateway already resolved the key's mode and signed it
 *       into the internal context (M15); {@link MerchantContextHolder} holds it. This mode
 *       is key-bound and never client-overridable — a {@code sk_test_} key physically
 *       cannot assert live.</li>
 *   <li><b>JWT / dashboard path</b> — no internal context. The caller (who owns both their
 *       test and live data, so this selects a partition rather than crossing a trust
 *       boundary) chooses via the {@code X-PF-Mode} header, validated against {@link Mode};
 *       an unrecognised value is a 400, not a silent default.</li>
 *   <li><b>Neither</b> — default to {@code "test"} (the dashboard opens in test mode, §3.1).</li>
 * </ol>
 */
@Component
public class RequestModeResolver {

    /**
     * The client-facing mode header for the JWT/dashboard path. Distinct from the signed
     * {@code X-PF-Internal-*} family (never client-settable); the gateway strips any inbound
     * copy of this header on the API-key {@code /v1} route so it can only ever influence the
     * JWT path, where it is safe.
     */
    public static final String MODE_HEADER = "X-PF-Mode";

    private final HttpServletRequest request;

    public RequestModeResolver(HttpServletRequest request) {
        this.request = request;
    }

    /** The current request's mode as its canonical lowercase value ({@code "test"}/{@code "live"}). */
    public String resolve() {
        Optional<MerchantContext> context = MerchantContextHolder.get();
        if (context.isPresent()) {
            return context.get().mode();
        }
        String header = request.getHeader(MODE_HEADER);
        if (StringUtils.hasText(header)) {
            try {
                return Mode.parse(header).value();
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid " + MODE_HEADER + " header: must be 'test' or 'live'.");
            }
        }
        return Mode.TEST.value();
    }
}
