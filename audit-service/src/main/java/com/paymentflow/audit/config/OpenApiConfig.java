package com.paymentflow.audit.config;

import com.paymentflow.common.openapi.PublicApiDocument;
import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
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

    /**
     * The error responses every operation on the public tier can return (M21.4). Declared
     * as beans rather than annotations because 401/403/429/500 are true of the tier rather
     * than of any operation, and 124 copies of the same four annotations is 124 chances to
     * miss one — invisibly, since the document would still render.
     */
    @Bean
    public OperationCustomizer publicApiErrorResponses() {
        return PublicApiDocument.errorResponseCustomizer();
    }

    /**
     * Puts the {@code ApiError} schema those responses reference into the document, and
     * describes the components every fragment shares (M21.7). One customizer because M21.3's
     * merge refuses to combine a component two services define differently.
     */
    @Bean
    public OpenApiCustomizer publicApiSharedSchemas() {
        return PublicApiDocument.sharedSchemaCustomizer();
    }
}
