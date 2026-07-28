package com.paymentflow.notification.config;

import com.paymentflow.common.openapi.PublicApiDocument;
import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * notification-service's fragment of the public API document (M21.2).
 *
 * <p>The document-level contract — title, version, server, and the {@code SecretKey}
 * scheme — comes from {@link PublicApiDocument}; see its javadoc for why that is shared
 * rather than repeated here.
 *
 * <p>Two tags, because registering where webhooks go and inspecting whether they arrived
 * are separate tasks a developer does at separate times — the first once during
 * integration, the second every time something looks wrong.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI notificationOpenApi() {
        return PublicApiDocument.forService(
                PublicApiDocument.tag("Webhook endpoints",
                        "Register and manage the URLs PaymentFlow sends events to."),
                PublicApiDocument.tag("Webhook deliveries",
                        "Inspect and replay individual webhook delivery attempts."));
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

    /** Puts the {@code ApiError} schema those responses reference into the document. */
    @Bean
    public OpenApiCustomizer publicApiErrorSchema() {
        return PublicApiDocument.errorSchemaCustomizer();
    }
}
