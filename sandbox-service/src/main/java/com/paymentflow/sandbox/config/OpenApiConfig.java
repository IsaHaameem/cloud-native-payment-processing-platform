package com.paymentflow.sandbox.config;

import com.paymentflow.common.openapi.PublicApiDocument;
import io.swagger.v3.oas.models.OpenAPI;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * sandbox-service's fragment of the public API document (M21.2).
 *
 * <p>The document-level contract — title, version, server, and the {@code SecretKey}
 * scheme — comes from {@link PublicApiDocument}; see its javadoc for why that is shared
 * rather than repeated here.
 *
 * <p><b>The one place the shared security requirement does not hold.</b>
 * {@code GET /v1/test/cards} is genuinely unauthenticated (§8.1) — the catalogue is
 * identical for every merchant, so it carries no merchant or mode context, and the gateway
 * permits it without a key. The document-level {@code SecretKey} requirement would
 * describe it as needing one, so {@code TestCardController} clears it with an empty
 * {@code @SecurityRequirements}. That is the right shape for it: the rule stays "every
 * endpoint requires a key", stated once, with one endpoint declaring itself the exception
 * rather than the rule being weakened to accommodate it.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI sandboxOpenApi() {
        return PublicApiDocument.forService(
                PublicApiDocument.tag("Test cards",
                        "The card tokens that drive each sandbox outcome. Public reference "
                                + "data — no API key required."),
                PublicApiDocument.tag("Simulations",
                        "Force the next authorization to a chosen outcome, for testing "
                                + "failure paths on demand."),
                PublicApiDocument.tag("Decisions",
                        "Why the sandbox decided each authorization the way it did."));
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
