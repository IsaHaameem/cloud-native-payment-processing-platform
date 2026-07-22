package com.paymentflow.common.autoconfigure;

import com.paymentflow.common.security.InternalContextFilter;
import com.paymentflow.common.security.InternalContextProperties;
import com.paymentflow.common.security.InternalContextSigner;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

/**
 * Provides {@link InternalContextFilter} for every servlet-based service (M15). The
 * filter verifies the gateway's HMAC-signed merchant context and authenticates the
 * request into Spring Security (D100). Reactive services (the gateway) never match
 * {@code SERVLET} — the gateway *produces* the signed context and ships its own reactive
 * filters instead (mirrors {@code CorrelationIdAutoConfiguration}'s split, D11/D25).
 *
 * <p><b>The filter must run INSIDE Spring Security's chain, not ahead of it.</b> A
 * servlet filter registered before {@code FilterChainProxy} sets an {@code Authentication}
 * that {@code SecurityContextHolderFilter} then replaces at the start of the chain, so the
 * request reaches {@code AuthorizationFilter} unauthenticated (the M15 E2E finding: an
 * {@code sk_test_}-only call 401'd even with valid signed headers). The filter is therefore
 * exposed as a plain bean with its automatic servlet-container registration disabled; each
 * servlet service adds it to its own {@code SecurityFilterChain} via
 * {@code http.addFilterBefore(internalContextFilter, AuthorizationFilter.class)}.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({Filter.class, AbstractAuthenticationToken.class})
@EnableConfigurationProperties(InternalContextProperties.class)
public class InternalContextAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public InternalContextSigner internalContextSigner() {
        return new InternalContextSigner();
    }

    @Bean
    @ConditionalOnMissingBean
    public InternalContextFilter internalContextFilter(
            InternalContextProperties properties, InternalContextSigner signer, ObjectMapper objectMapper) {
        return new InternalContextFilter(properties, signer, objectMapper);
    }

    /**
     * Disables Boot's automatic servlet-container registration of the
     * {@link InternalContextFilter} bean above — it is added inside each service's Spring
     * Security chain instead (see class javadoc), so it must not also run as a standalone
     * filter ahead of that chain.
     */
    @Bean
    @ConditionalOnMissingBean(name = "internalContextFilterRegistration")
    public FilterRegistrationBean<InternalContextFilter> internalContextFilterRegistration(
            InternalContextFilter internalContextFilter) {
        FilterRegistrationBean<InternalContextFilter> registration =
                new FilterRegistrationBean<>(internalContextFilter);
        registration.setEnabled(false);
        return registration;
    }
}
