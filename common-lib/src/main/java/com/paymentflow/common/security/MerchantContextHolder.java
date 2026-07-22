package com.paymentflow.common.security;

import java.util.Optional;

/**
 * Request-scoped access to the current {@link MerchantContext}, set by
 * {@code InternalContextFilter} for API-key-authenticated requests only. Absent
 * (empty) for every JWT-authenticated request — callers must treat that as "resolve
 * the merchant the existing way," never as an error.
 *
 * <p>A plain {@link ThreadLocal} rather than a Spring {@code @RequestScope} bean: the
 * filter that populates it runs before the Spring Security/DispatcherServlet chain,
 * where the request-scope proxy is not guaranteed to be usable yet. Always cleared in
 * the filter's {@code finally} block, mirroring {@code CorrelationIdFilter}'s MDC
 * cleanup discipline, so a pooled request thread never leaks context into the next
 * request it happens to serve.
 */
public final class MerchantContextHolder {

    private static final ThreadLocal<MerchantContext> CONTEXT = new ThreadLocal<>();

    private MerchantContextHolder() {
    }

    public static void set(MerchantContext context) {
        CONTEXT.set(context);
    }

    public static Optional<MerchantContext> get() {
        return Optional.ofNullable(CONTEXT.get());
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
