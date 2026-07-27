package com.paymentflow.audit.config;

import com.paymentflow.common.openapi.PublicApiDocument;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * audit-service's fragment of the public API document (M21.2).
 *
 * <p>The document-level contract — title, version, server, and the {@code SecretKey}
 * scheme — comes from {@link PublicApiDocument}; see its javadoc for why that is shared
 * rather than repeated here.
 *
 * <p>One resource. The tag is {@code Events} rather than {@code Audit} because that is
 * what the object is called everywhere a merchant meets it: the {@code evt_} id in a
 * webhook body, the {@code /v1/events} path, and {@code CanonicalEventType}'s vocabulary
 * (D140). "Audit" is this platform's word for the service, not the merchant's word for the
 * thing.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI auditOpenApi() {
        return PublicApiDocument.forService(
                PublicApiDocument.tag("Events",
                        "The log of everything that happened on your account, and the "
                                + "source of every webhook you receive."));
    }
}
