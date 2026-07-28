package com.paymentflow.common.openapi;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The standard error responses applied to every published operation (M21.4).
 *
 * <p>The interesting cases are the two exceptions — an operation that documents its own
 * version of a status, and the one endpoint that takes no credential — because both are
 * ways a blanket customizer can quietly publish something untrue.
 */
class PublicApiErrorResponsesTest {

    @Test
    void anAuthenticatedOperationGetsTheFullSet() {
        Operation operation = PublicApiErrorResponses.apply(new Operation());

        assertThat(operation.getResponses().keySet())
                .containsExactlyInAnyOrder("401", "403", "429", "500");
    }

    @Test
    void everyErrorResponseReferencesApiErrorAndCarriesAnExample() {
        Operation operation = PublicApiErrorResponses.apply(new Operation());

        assertThat(operation.getResponses().values()).allSatisfy(response -> {
            var json = response.getContent().get("application/json");
            assertThat(json.getSchema().get$ref()).isEqualTo("#/components/schemas/ApiError");
            // §9.2: every documented response shows a body. An error example is the one most
            // worth showing — it is where a reader learns `type` is the field to branch on.
            assertThat(json.getExample()).isNotNull();
            assertThat(response.getDescription()).isNotBlank();
        });
    }

    @Test
    void anOperationThatDocumentsItsOwnVersionOfAStatusKeepsIt() {
        // A per-operation 403 says something this customizer cannot know. Overwriting it
        // would silently downgrade precise documentation to generic documentation, and the
        // document would still look complete.
        Operation operation = new Operation().responses(new ApiResponses()
                .addApiResponse("403", new ApiResponse().description("This payment belongs to another merchant.")));

        PublicApiErrorResponses.apply(operation);

        assertThat(operation.getResponses().get("403").getDescription())
                .isEqualTo("This payment belongs to another merchant.");
        assertThat(operation.getResponses().keySet()).contains("401", "429", "500");
    }

    @Test
    void anUnauthenticatedOperationIsNotDocumentedAsReturning401Or403() {
        // GET /v1/test/cards (§8.1) is the platform's one endpoint that takes no credential,
        // and it declares `security: []` to say so. Documenting a 401 on it would be the
        // same defect M21.2 fixed from the other direction — a document contradicting the
        // running system — and would make an SDK generate credential handling for a call
        // that takes none. Found by reading the generated baseline, not by inspection.
        Operation operation = new Operation().security(List.of());

        PublicApiErrorResponses.apply(operation);

        assertThat(operation.getResponses().keySet())
                .describedAs("an endpoint that needs no credential cannot fail to authenticate")
                .containsExactlyInAnyOrder("429", "500");
    }

    @Test
    void anOperationWithAStatedSecurityRequirementStillGetsTheFullSet() {
        // `security: []` is an opt-out; a *stated* requirement is not. The two are different
        // things and swagger models them differently, so the check cannot be "has security".
        Operation operation = new Operation()
                .security(List.of(new SecurityRequirement().addList("SecretKey")));

        PublicApiErrorResponses.apply(operation);

        assertThat(operation.getResponses().keySet())
                .containsExactlyInAnyOrder("401", "403", "429", "500");
    }

    @Test
    void anOperationWithNoSecurityStatedInheritsTheDocumentRequirementAndGetsTheFullSet() {
        // null means "inherits the document-level requirement", which is how all but one
        // operation on this platform are declared.
        Operation operation = new Operation();
        assertThat(operation.getSecurity()).isNull();

        PublicApiErrorResponses.apply(operation);

        assertThat(operation.getResponses().keySet()).contains("401", "403");
    }
}
