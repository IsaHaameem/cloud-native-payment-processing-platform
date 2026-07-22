package com.paymentflow.common.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;

/**
 * The {@link org.springframework.security.core.Authentication} populated for an
 * API-key-authenticated request (M15) — satisfies each service's existing
 * {@code anyRequest().authenticated()} rule exactly like a parsed JWT would, without
 * any service's {@code SecurityConfig} needing to know a second credential type
 * exists. Each granted scope becomes a {@code SCOPE_<scope>} authority (including the
 * literal wildcard scope as {@code SCOPE_*}), ready for a future
 * {@code @PreAuthorize("hasAuthority(...)")} check without redesigning this class.
 */
public class MerchantContextAuthenticationToken extends AbstractAuthenticationToken {

    private final MerchantContext context;

    public MerchantContextAuthenticationToken(MerchantContext context) {
        super(authorities(context));
        this.context = context;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return context;
    }

    @Override
    public String getName() {
        return context.merchantId().toString();
    }

    public MerchantContext merchantContext() {
        return context;
    }

    private static Collection<? extends GrantedAuthority> authorities(MerchantContext context) {
        return context.scopes().stream()
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList();
    }
}
