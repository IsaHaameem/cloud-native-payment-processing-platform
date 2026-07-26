package com.paymentflow.common.autoconfigure;

import com.paymentflow.common.query.CursorCodec;
import com.paymentflow.common.security.InternalContextProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Provides the {@link CursorCodec} every public list endpoint uses to issue and verify
 * pagination cursors (M19, D107/D138).
 *
 * <p>Servlet-only, like {@link InternalContextAutoConfiguration}: the gateway routes
 * list requests but never builds a page itself, so it has no use for a codec. Keyed on
 * the same secret as the internal context — see {@link CursorCodec}'s javadoc for why a
 * fourth managed key would buy no separation that matters.
 *
 * <p>Registered as its own auto-configuration rather than folded into
 * {@code InternalContextAutoConfiguration} because that class is conditional on Spring
 * Security being present, and a cursor has nothing to do with authentication — a
 * service could legitimately want paginated reads without it.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(InternalContextProperties.class)
public class QueryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CursorCodec cursorCodec(InternalContextProperties properties) {
        return new CursorCodec(properties.secret());
    }
}
