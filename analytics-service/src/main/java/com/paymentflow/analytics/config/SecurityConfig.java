package com.paymentflow.analytics.config;

import com.paymentflow.common.security.InternalContextFilter;
import com.paymentflow.analytics.security.RestAccessDeniedHandler;
import com.paymentflow.analytics.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * analytics-service's first security configuration (M19.6). analytics-service consumed events and served nothing (D42).
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
                        // M21.2: the OpenAPI description of the public /v1 tier (D148).
                        // Permitted for the same reason the Prometheus scrape is — it is
                        // read by infrastructure holding no merchant credential: M21.3's
                        // merge task, the contract tests, and CI's breaking-change diff.
                        //
                        // It discloses nothing a caller is not already entitled to know:
                        // its entire content is the public API surface, which M21.3
                        // commits as `openapi.yaml` and M25 publishes. Requiring a key to
                        // fetch the description of how to use a key would be circular.
                        //
                        // Not reachable from outside regardless: the gateway routes only
                        // its explicit path predicates, and /v3/api-docs is not among them.
                        //
                        // `/v3/api-docs.yaml` is listed separately and is not a
                        // redundancy: springdoc serves YAML from a *sibling* path, not a
                        // child, so `/v3/api-docs/**` does not cover it.
                        .requestMatchers(HttpMethod.GET,
                                "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }
}
