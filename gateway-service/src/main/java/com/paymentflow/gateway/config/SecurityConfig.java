package com.paymentflow.gateway.config;

import com.paymentflow.gateway.security.RestServerAccessDeniedHandler;
import com.paymentflow.gateway.security.RestServerAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * Stateless, reactive edge security across <b>two</b> filter chains (M15 adds the
 * first one; the second is V1's original chain, unchanged in substance):
 * <ul>
 *   <li><b>{@code /v1/**}</b> (chain #1, evaluated first): no {@code oauth2ResourceServer}
 *       at all — a session JWT is never accepted here (D114). Authorization relies
 *       entirely on whatever {@link org.springframework.security.core.context.SecurityContext}
 *       {@code ApiKeyAuthenticationWebFilter} already populated via
 *       {@code ReactiveSecurityContextHolder.withAuthentication(...)} before this chain
 *       ever runs (that filter is a plain {@code WebFilter}, registered well ahead of
 *       Spring Security's own filter proxy). This split exists because Spring
 *       Security's OAuth2 resource-server filter does not defer to a pre-populated
 *       context — it attempts to parse <i>any</i> {@code Authorization: Bearer ...}
 *       header as a JWT unconditionally, which would 401 every {@code sk_test_...}
 *       credential before the API-key path ever got a chance. A single shared chain
 *       could not express "authenticate this path a completely different way,"
 *       only "skip authentication here" (which would be fail-open, not fail-closed) —
 *       hence two chains, not one chain with an extra {@code permitAll()}.</li>
 *   <li><b>everything else</b> (chain #2): byte-for-byte V1's original rule set — auth
 *       endpoints/JWKS/health public, everything else requires a valid RS256 access
 *       token verified against identity-service's JWKS. Untouched, not refactored.</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityWebFilterChain apiKeySecurityWebFilterChain(ServerHttpSecurity http,
                                                                RestServerAuthenticationEntryPoint authenticationEntryPoint,
                                                                RestServerAccessDeniedHandler accessDeniedHandler) {
        http
                .securityMatcher(new PathPatternParserServerWebExchangeMatcher("/v1/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .headers(securityHeaders())
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyExchange().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                          ReactiveJwtDecoder jwtDecoder,
                                                          CorsConfigurationSource corsConfigurationSource,
                                                          RestServerAuthenticationEntryPoint authenticationEntryPoint,
                                                          RestServerAccessDeniedHandler accessDeniedHandler) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .headers(securityHeaders())
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/api/v1/auth/**", "/oauth2/jwks").permitAll()
                        .pathMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/**").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtDecoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    private static Customizer<ServerHttpSecurity.HeaderSpec> securityHeaders() {
        return headers -> headers
                .frameOptions(frame -> frame.mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                .referrerPolicy(referrer -> referrer
                        .policy(ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .permissionsPolicy(permissions -> permissions.policy("geolocation=(), camera=(), microphone=()"))
                .hsts(hsts -> hsts.includeSubdomains(true).maxAge(Duration.ofDays(365)));
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder(IdentityServiceProperties identityProperties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(identityProperties.jwksUri()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(identityProperties.issuer()));
        return decoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(GatewayCorsProperties corsProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
