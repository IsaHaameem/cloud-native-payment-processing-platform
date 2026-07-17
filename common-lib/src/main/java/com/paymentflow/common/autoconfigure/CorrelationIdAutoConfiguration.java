package com.paymentflow.common.autoconfigure;

import com.paymentflow.common.correlation.CorrelationIdFilter;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Registers {@link CorrelationIdFilter} for servlet-based services. Reactive services
 * (e.g. the API gateway) do not match {@code SERVLET} and get their own equivalent.
 *
 * <p>The filter is registered via a {@link FilterRegistrationBean} (rather than a raw
 * {@code Filter} bean) so its order and URL mapping are explicit and it is not
 * double-registered.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(Filter.class)
public class CorrelationIdAutoConfiguration {

    /** Ordered ahead of Spring Security so authentication and logging carry the ids. */
    public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    @ConditionalOnMissingBean(name = "correlationIdFilterRegistration")
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(FILTER_ORDER);
        registration.setName("correlationIdFilter");
        return registration;
    }
}
