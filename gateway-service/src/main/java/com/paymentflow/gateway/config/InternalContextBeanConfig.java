package com.paymentflow.gateway.config;

import com.paymentflow.common.security.InternalContextProperties;
import com.paymentflow.common.security.InternalContextSigner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * common-lib's {@code InternalContextAutoConfiguration} is {@code SERVLET}-conditional
 * (D11's established split — it stays correctly inactive on the reactive gateway),
 * which also means it never registers an {@link InternalContextSigner} bean or binds
 * {@link InternalContextProperties} here. The gateway is the one service that
 * <em>signs</em> the internal context rather than verifying it, so both need
 * registering explicitly — {@code InternalContextSigner} is stateless with no
 * dependencies, so a plain {@code @Bean} method is all that's needed for it.
 */
@Configuration
@EnableConfigurationProperties(InternalContextProperties.class)
public class InternalContextBeanConfig {

    @Bean
    public InternalContextSigner internalContextSigner() {
        return new InternalContextSigner();
    }
}
