package com.paymentflow.payment.config;

import com.paymentflow.common.security.InternalContextFilter;
import com.paymentflow.payment.security.RestAccessDeniedHandler;
import com.paymentflow.payment.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Stateless resource-server security: payment-service holds no signing key of its own
 * — it validates RS256 access tokens against identity-service's JWKS (D17). Health is
 * public; every other request requires a valid token. Ownership (does this token's
 * subject's merchant own this payment) is enforced in the service layer via
 * {@code MerchantResolver} + merchant-scoped repository queries, not here — there is
 * no role-gated endpoint in this service, so unlike identity/merchant-service,
 * {@code @EnableMethodSecurity} is not wired (it would guard nothing).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtDecoder jwtDecoder,
                                           RestAuthenticationEntryPoint authenticationEntryPoint,
                                           RestAccessDeniedHandler accessDeniedHandler,
                                           InternalContextFilter internalContextFilter) throws Exception {
        http
                // API-key requests arrive from the gateway carrying a signed internal
                // merchant context instead of a JWT (M15). This filter verifies that
                // context and authenticates the request; it runs INSIDE the chain (not as a
                // standalone servlet filter) so the authentication it sets survives
                // SecurityContextHolderFilter and reaches AuthorizationFilter. A request
                // with no internal-context header is untouched and falls through to the JWT
                // resource-server path below, exactly as before M15.
                .addFilterBefore(internalContextFilter, AuthorizationFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/**").permitAll()
                        // M21.1: the OpenAPI description of the public /v1 tier. Permitted
                        // for the same reason the Prometheus scrape is — it is read by
                        // infrastructure that holds no merchant credential: M21's merge
                        // task, the contract tests, and CI's breaking-change diff.
                        //
                        // It discloses nothing a caller is not already entitled to know:
                        // its entire content is the public API surface, which M21 commits
                        // as `openapi.yaml` and M25 publishes on the documentation site.
                        // Requiring a key to fetch the description of how to use a key
                        // would be circular.
                        //
                        // Not reachable from outside regardless: the gateway routes only
                        // the explicit paths listed in its route predicates, and
                        // /v3/api-docs is not among them.
                        //
                        // `/v3/api-docs.yaml` is listed separately and is not a
                        // redundancy: springdoc serves YAML from a *sibling* path, not a
                        // child, so `/v3/api-docs/**` does not cover it.
                        .requestMatchers(HttpMethod.GET,
                                "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(IdentityServiceProperties identityProperties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(identityProperties.jwksUri()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(identityProperties.issuer()));
        return decoder;
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
}
