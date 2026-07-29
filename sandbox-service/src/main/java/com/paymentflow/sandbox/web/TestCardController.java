package com.paymentflow.sandbox.web;

import com.paymentflow.sandbox.dto.TestCardResponse;
import com.paymentflow.sandbox.mapper.TestCardMapper;
import com.paymentflow.sandbox.service.TestCardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public, unauthenticated reference-data endpoint (§8.1) — the catalogue is identical
 * for every merchant, so it carries no merchant or mode context, unlike every other
 * sandbox endpoint. Also the single source the documentation (M17.8) renders from, so
 * the docs and a real call can never drift.
 */
@RestController
public class TestCardController {

    /** Declared with its description in {@code OpenApiConfig}. */
    static final String TEST_CARDS_TAG = "Test cards";

    private final TestCardService testCardService;
    private final TestCardMapper testCardMapper;

    public TestCardController(TestCardService testCardService, TestCardMapper testCardMapper) {
        this.testCardService = testCardService;
        this.testCardMapper = testCardMapper;
    }

    /**
     * The one operation in the published API that takes no credential, so it is the one
     * place the document-level {@code SecretKey} requirement has to be cleared.
     *
     * <p>An empty {@code @SecurityRequirements} emits {@code security: []} on the
     * operation, which in OpenAPI means "no security" rather than "unspecified" — the
     * distinction matters, because an omitted {@code security} key inherits the document's
     * and would describe this endpoint as needing a key it neither requires nor reads. The
     * gateway genuinely permits it unauthenticated (§8.1), so the alternative is a
     * document that contradicts the running system and SDKs that refuse to call it without
     * a key the caller may not have yet.
     */
    @GetMapping("/v1/test/cards")
    @Operation(tags = TEST_CARDS_TAG, operationId = "listTestCards",
            summary = "List the test cards",
            description = """
                    Returns the catalogue of tokens you can pass as `paymentMethodToken` in \
                    test mode, and exactly what each one will do — approve, decline with a \
                    given code, fail with an error, stall, or settle after a delay.

                    **This endpoint needs no API key.** It is the same catalogue for every \
                    merchant, and requiring a credential to read the list of credentials-free \
                    test data would be a poor first impression; it is the one endpoint in the \
                    public API that takes no authentication.

                    The catalogue is the single source the documentation renders from, so \
                    what you read here and what a real call does cannot drift.""")
    @ApiResponse(responseCode = "200", description = "Every test card the sandbox recognises.")
    @SecurityRequirements
    public List<TestCardResponse> listCards() {
        return testCardService.listActive().stream().map(testCardMapper::toResponse).toList();
    }
}
