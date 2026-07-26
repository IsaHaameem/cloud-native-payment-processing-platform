package com.paymentflow.transaction.config;

import com.paymentflow.common.security.InternalContextFilter;
import com.paymentflow.transaction.security.RestAccessDeniedHandler;
import com.paymentflow.transaction.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * transaction-service's first security configuration (M19.4). The ledger has had no HTTP surface at all until now (D42). Its read-only constraint is preserved by construction rather than by this configuration: there is no write endpoint to protect, because none was built.
 *
 * <p>Like sandbox-service (M17.2) and notification-service (M18.2), there is <b>no
 * OAuth2 resource server</b>: this service never accepts a JWT, because the dashboard
 * tier that would present one is deferred to M23 (D133). {@link InternalContextFilter}
 * is both the only authentication mechanism it needs and the only one it has.
 *
 * <p>The filter is registered <i>inside</i> the chain via {@code addFilterBefore(...,
 * AuthorizationFilter.class)} rather than ahead of it (D124): a filter running before
 * the chain sets an {@code Authentication} that {@code SecurityContextHolderFilter}
 * then replaces, so the request would reach {@code AuthorizationFilter} unauthenticated.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           RestAuthenticationEntryPoint authenticationEntryPoint,
                                           RestAccessDeniedHandler accessDeniedHandler,
                                           InternalContextFilter internalContextFilter) throws Exception {
        http
                .addFilterBefore(internalContextFilter, AuthorizationFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }
}
