package com.paymentflow.agentic.config;

import com.paymentflow.common.security.InternalContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * This service accepts exactly one authenticated caller — payment-service, asking for a
 * provider decision over the HMAC-signed internal context (D100) — and one unauthenticated
 * surface, the demo API.
 *
 * <p>There is <b>no OAuth2 resource server</b>, for the same reason sandbox-service has none:
 * no JWT ever reaches this service by any route, so adding a resource server would be adding
 * a credential path nobody uses and everybody would then have to reason about.
 *
 * <p><b>{@code /internal/v1/**} is {@code permitAll} at this layer, and that is not a
 * weakening.</b> It matches the precedent merchant-service and sandbox-service both set:
 * {@link InternalContextFilter} runs inside this chain, ahead of {@link AuthorizationFilter},
 * and rejects a missing, malformed, stale or forged context with 401 on its own authority
 * before any controller runs. The provider-decision controller additionally requires a
 * verified merchant context and refuses without one. Two independent checks.
 *
 * <p><b>{@code /api/agentic/**} requires a verified internal context.</b> It is reached only
 * through the developer portal's server-side proxy, which derives merchant, mode and user from
 * the authenticated session and signs the same HMAC context the gateway signs for {@code /v1}
 * (D100/D185) — so this surface authenticates exactly the way {@code /internal/v1/**} does, via
 * {@link InternalContextFilter}, and adds no second auth mechanism. An unsigned, stale or
 * forged request is refused with 401 by that filter before any controller runs; a request with
 * no context at all falls through to {@code authenticated()} and is refused there. The browser
 * never reaches this service directly and never holds the signing secret.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, InternalContextFilter internalContextFilter)
            throws Exception {
        http
                .addFilterBefore(internalContextFilter, AuthorizationFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Without this the servlet default is Http403ForbiddenEntryPoint, so a request
                // with no context at all would answer 403 while one with a bad context answers
                // 401 (InternalContextFilter). One answer for "authenticate": 401.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/**").permitAll()
                        .requestMatchers("/internal/v1/**").permitAll()
                        // Not permitAll: reached only through the portal's signed server-side
                        // proxy. InternalContextFilter authenticates a valid context ahead of
                        // this line; anything without one is refused here.
                        .requestMatchers("/api/agentic/**").authenticated()
                        .anyRequest().authenticated());
        return http.build();
    }
}
