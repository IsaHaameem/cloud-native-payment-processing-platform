package com.paymentflow.transaction.config;

import com.paymentflow.common.openapi.PublicApiDocument;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * transaction-service's fragment of the public API document (M21.2).
 *
 * <p>The document-level contract — title, version, server, and the {@code SecretKey}
 * scheme — comes from {@link PublicApiDocument}; see its javadoc for why that is shared
 * rather than repeated here. What this class contributes is the two resources this service
 * owns.
 *
 * <p>Two tags rather than one: the current balance and the ledger entries behind it are
 * different objects with different shapes, and a merchant reconciling a payout is looking
 * for one or the other, not for "transaction-service".
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI transactionOpenApi() {
        return PublicApiDocument.forService(
                PublicApiDocument.tag("Balance",
                        "Your available and pending balance, per currency."),
                PublicApiDocument.tag("Balance transactions",
                        "The ledger entries that make up your balance."));
    }
}
