package com.paymentflow.payment.config;

import com.paymentflow.common.openapi.PublicApiDocument;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * payment-service's fragment of the public API document (M21.1, generalised in M21.2).
 *
 * <p>Everything that is true of the API as a whole — its title, the date-based contract
 * version, the public server URL, and the {@code SecretKey} scheme — lives in
 * {@link PublicApiDocument}, shared by all six services that publish a {@code /v1} tier so
 * that the fragments M21.3 merges cannot disagree with one another. What remains here is
 * the only part that is genuinely this service's: the resources it owns.
 *
 * <p><b>What this document deliberately describes.</b> Only the {@code /v1} tier, which is
 * the public promise. {@code /api/v1} (the dashboard tier, D98) and {@code /internal/v1}
 * (service-to-service) are excluded by {@code springdoc.paths-to-match} in
 * {@code application.yaml}, because §9.5 is explicit that publishing them "would imply a
 * promise the platform does not intend to make". The exclusion is asserted by
 * {@code OpenApiDocumentIntegrationTest} rather than left to configuration nobody
 * re-reads.
 *
 * <p><b>Two tags, not one.</b> This service serves two resources; a reader looking for
 * refunds should not have to know they happen to be implemented next to payments.
 */
@Configuration
public class OpenApiConfig {

    /**
     * Retained as the spelling the rest of payment-service and its tests refer to. The
     * value is the shared contract version — there is one public API revision, not one
     * per service.
     */
    public static final String API_VERSION = PublicApiDocument.API_VERSION;

    @Bean
    public OpenAPI paymentFlowOpenApi() {
        return PublicApiDocument.forService(
                PublicApiDocument.tag("Payments",
                        "Create, authorize, capture, void, and read payments."),
                PublicApiDocument.tag("Refunds",
                        "Read the refunds issued against your payments."));
    }
}
