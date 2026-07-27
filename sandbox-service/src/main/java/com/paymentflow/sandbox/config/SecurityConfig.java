package com.paymentflow.sandbox.config;

import com.paymentflow.common.security.InternalContextFilter;
import com.paymentflow.sandbox.security.RestAccessDeniedHandler;
import com.paymentflow.sandbox.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Sandbox-service never accepts a JWT — its only authenticated callers are
 * payment-service (the internal decision endpoint, signed internal context, D100) and,
 * from M17.5, the gateway on behalf of an {@code sk_test_} API key (the same signed
 * mechanism, a different signer). There is no OAuth2 resource server here at all,
 * unlike every other servlet service: {@link InternalContextFilter} is both the only
 * authentication mechanism this service needs and the only one it has.
 *
 * <p>{@code /v1/test/cards} is genuinely public reference data (§8.1) and stays
 * {@code permitAll} indefinitely, matching {@code analytics-service}'s "no auth
 * surface" scope discipline for its one non-sensitive read.
 *
 * <p>{@code /internal/v1/**} is also {@code permitAll} at this layer — matching
 * merchant-service's identical precedent for its own internal-only endpoint
 * ("unreachable from outside regardless: no gateway route predicate ever matches
 * {@code /internal/v1/**}"). This is not a weaker check: {@link InternalContextFilter}
 * still verifies the HMAC signature unconditionally (a missing/invalid context is
 * rejected by the filter itself, before the controller runs), and the controller
 * additionally requires a verified {@code MerchantContextHolder} value or rejects with
 * 401 itself. The real reason it can't be {@code .anyRequest().authenticated()}
 * instead: this endpoint's controller returns a {@code CompletableFuture} (M17.2's
 * non-blocking latency simulation), and Spring Security's built-in async integration
 * (@{@code WebAsyncManagerIntegrationFilter}) only propagates the
 * {@code SecurityContext} across a {@code Callable} return type, not a
 * {@code CompletableFuture}/{@code DeferredResult} — {@code AuthorizationFilter} would
 * see no authentication on the async re-dispatch and reject a genuinely-authenticated
 * request with 401. Gating on the filter's own verification plus the controller's
 * explicit check sidesteps that gap entirely rather than fighting it.
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
                        .requestMatchers(HttpMethod.GET, "/v1/test/cards").permitAll()
                        .requestMatchers("/internal/v1/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }
}
