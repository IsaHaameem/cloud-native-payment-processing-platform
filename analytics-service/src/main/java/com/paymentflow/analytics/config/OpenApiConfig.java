package com.paymentflow.analytics.config;

import com.paymentflow.common.openapi.PublicApiDocument;
import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * analytics-service's fragment of the public API document (M21.2).
 *
 * <p>The document-level contract — title, version, server, and the {@code SecretKey}
 * scheme — comes from {@link PublicApiDocument}; see its javadoc for why that is shared
 * rather than repeated here.
 *
 * <p>Three tags for three genuinely different questions, even though one service answers
 * all of them: what my payments did (analytics), what my integration sent (request logs),
 * and what I am being metered for (usage). Grouping them under one heading because they
 * share a database would organise the documentation site around this platform's internals
 * rather than around what a developer came to find out.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI analyticsOpenApi() {
        return PublicApiDocument.forService(
                PublicApiDocument.tag("Analytics",
                        "Payment volume, success rate, and totals over a time range."),
                PublicApiDocument.tag("Request logs",
                        "Every API request your keys made, with status and timing."),
                PublicApiDocument.tag("Usage",
                        "Your metered request counts, aggregated per UTC day."));
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
