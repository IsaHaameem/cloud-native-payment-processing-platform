package com.paymentflow.notification.config;

import com.paymentflow.common.security.InternalContextFilter;
import com.paymentflow.notification.security.RestAccessDeniedHandler;
import com.paymentflow.notification.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * notification-service's first security configuration (M18.2). Like sandbox-service
 * (M17.2) and unlike every other servlet service, there is <b>no OAuth2 resource
 * server</b> here at all: this service never accepts a JWT, because the {@code /api/v1}
 * dashboard mirror is deferred to M23 (D133). {@link InternalContextFilter} is both the
 * only authentication mechanism it needs and the only one it has — a gateway-asserted,
 * HMAC-signed merchant context on behalf of an API key (D100).
 *
 * <p>The filter is registered <i>inside</i> the chain via {@code addFilterBefore(...,
 * AuthorizationFilter.class)} rather than ahead of it (D124): a filter that runs before
 * the chain sets an {@code Authentication} that {@code SecurityContextHolderFilter}
 * then replaces, so the request would reach {@code AuthorizationFilter} unauthenticated.
 *
 * <p>{@code .anyRequest().authenticated()} is the catch-all, satisfied for a valid
 * signed context by the {@code MerchantContextAuthenticationToken} the filter populates.
 * Unlike sandbox-service, no {@code permitAll()} carve-out is needed for the API itself:
 * every method on {@code WebhookEndpointController} returns a plain value, never a
 * {@code CompletableFuture}, so Spring Security's async-dispatch gap (which forced
 * sandbox-service's {@code /internal/v1/**} carve-out) does not arise here.
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
