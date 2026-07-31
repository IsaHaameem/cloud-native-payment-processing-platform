package com.paymentflow.sdk.codegen;

import com.paymentflow.openapi.OpenApiYaml;
import com.paymentflow.sdk.codegen.SdkSpec.SdkModel;
import com.paymentflow.sdk.codegen.SdkSpec.SdkType.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the contract's shapes are understood (M22.1).
 *
 * <p>This is the class that decides what both SDKs believe, so a wrong answer here is a wrong
 * answer in two languages at once and in the same way — which is precisely the failure that
 * would survive a cross-language parity test. These tests are the only thing that can catch
 * it.
 */
class SdkSpecReaderTest {

    private static SdkSpec read(String yaml) {
        return new SdkSpecReader().read(OpenApiYaml.read(yaml));
    }

    private static SdkModel model(SdkSpec spec, String name) {
        return spec.models().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static final String MINIMAL_HEADER = """
            openapi: 3.1.0
            info:
              title: PaymentFlow API
              version: "2026-08-01"
            servers:
            - url: https://api.paymentflow.dev
            """;

    @Test
    void theDocumentsIdentityIsReadFromTheInfoBlockAndTheFirstServer() {
        SdkSpec spec = read(MINIMAL_HEADER + """
                paths: {}
                components:
                  schemas: {}
                """);

        assertThat(spec.apiVersion()).isEqualTo("2026-08-01");
        assertThat(spec.title()).isEqualTo("PaymentFlow API");
        // The published host, which is what every generated client is pointed at. springdoc
        // would otherwise have inferred it from whichever request produced the fragment.
        assertThat(spec.serverUrl()).isEqualTo("https://api.paymentflow.dev");
    }

    @Test
    void nullabilityIsReadFromTheThreeOneTypeUnionRatherThanAThreeZeroFlag() {
        SdkSpec spec = read(MINIMAL_HEADER + """
                paths: {}
                components:
                  schemas:
                    Summary:
                      type: object
                      properties:
                        successRate:
                          type:
                          - number
                          - "null"
                          format: double
                        totalCount:
                          type: integer
                """);

        // D143's `successRate` is the reason this matters: it is explicitly null rather than
        // omitted when nothing was measured, and an SDK that typed it as a plain number would
        // tell a caller a value is always there when the platform deliberately says otherwise.
        assertThat(model(spec, "Summary").fields())
                .filteredOn(field -> field.name().equals("successRate"))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.type().kind()).isEqualTo(Kind.NUMBER);
                    assertThat(field.type().nullable()).isTrue();
                });
        assertThat(model(spec, "Summary").fields())
                .filteredOn(field -> field.name().equals("totalCount"))
                .singleElement()
                .satisfies(field -> assertThat(field.type().nullable()).isFalse());
    }

    @Test
    void formatsThatChangeHowAValueIsReadAreKeptAndOthersAreNot() {
        SdkSpec spec = read(MINIMAL_HEADER + """
                paths: {}
                components:
                  schemas:
                    Thing:
                      type: object
                      properties:
                        id:
                          type: string
                          format: uuid
                        createdAt:
                          type: string
                          format: date-time
                        day:
                          type: string
                          format: date
                        name:
                          type: string
                          format: something-nobody-has-a-rule-for
                """);

        assertThat(model(spec, "Thing").fields())
                .extracting(field -> field.type().kind())
                .containsExactly(Kind.DATE_TIME, Kind.DATE, Kind.UUID, Kind.STRING);
    }

    @Test
    void anObjectWithAdditionalPropertiesIsAMapAndOneWithoutIsFreeForm() {
        SdkSpec spec = read(MINIMAL_HEADER + """
                paths: {}
                components:
                  schemas:
                    Thing:
                      type: object
                      properties:
                        metadata:
                          type: object
                          additionalProperties:
                            type: string
                        data:
                          type: object
                """);

        assertThat(model(spec, "Thing").fields())
                .filteredOn(field -> field.name().equals("metadata"))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.type().kind()).isEqualTo(Kind.MAP);
                    assertThat(field.type().item().kind()).isEqualTo(Kind.STRING);
                });
        // audit-service stores the event payload verbatim as an opaque tree (D44); typing it
        // as anything narrower would be a claim about a shape the platform refuses to make.
        assertThat(model(spec, "Thing").fields())
                .filteredOn(field -> field.name().equals("data"))
                .singleElement()
                .satisfies(field -> assertThat(field.type().kind()).isEqualTo(Kind.OBJECT));
    }

    @Test
    void anInlineEnumIsNamedAfterTheModelAndFieldThatDeclareIt() {
        SdkSpec spec = read(MINIMAL_HEADER + """
                paths: {}
                components:
                  schemas:
                    PaymentResponse:
                      type: object
                      properties:
                        mode:
                          type: string
                          enum:
                          - test
                          - live
                    RefundResponse:
                      type: object
                      properties:
                        mode:
                          type: string
                          enum:
                          - test
                          - live
                """);

        // Two identical vocabularies, two aliases. Collapsing them would mean naming the
        // survivor arbitrarily and would couple two resources that are only accidentally
        // alike — the day one grows a third mode, the shared alias would widen the other.
        assertThat(spec.enums()).extracting(SdkSpec.SdkEnum::name)
                .containsExactly("PaymentResponseMode", "RefundResponseMode");
        assertThat(spec.enums().getFirst().values()).containsExactly("test", "live");
    }

    @Test
    void anOperationIsReadWithItsIdMethodPathAndDocumentedSuccess() {
        SdkSpec spec = read(MINIMAL_HEADER + """
                paths:
                  /v1/payments:
                    post:
                      operationId: createPayment
                      tags:
                      - Payments
                      summary: Create a payment.
                      parameters:
                      - name: Idempotency-Key
                        in: header
                        required: true
                        schema:
                          type: string
                      requestBody:
                        content:
                          application/json:
                            schema:
                              $ref: "#/components/schemas/CreatePaymentRequest"
                      responses:
                        "201":
                          content:
                            application/json:
                              schema:
                                $ref: "#/components/schemas/PaymentResponse"
                components:
                  schemas: {}
                """);

        assertThat(spec.operations()).singleElement().satisfies(operation -> {
            assertThat(operation.id()).isEqualTo("createPayment");
            assertThat(operation.method()).isEqualTo("POST");
            assertThat(operation.path()).isEqualTo("/v1/payments");
            assertThat(operation.tag()).isEqualTo("Payments");
            assertThat(operation.requestModel()).isEqualTo("CreatePaymentRequest");
            assertThat(operation.success().status()).isEqualTo("201");
            assertThat(operation.success().model()).isEqualTo("PaymentResponse");
        });
    }

    @Test
    void aPathItemsNonOperationKeysAreNotMistakenForOperations() {
        SdkSpec spec = read(MINIMAL_HEADER + """
                paths:
                  /v1/payments/{id}:
                    summary: A payment.
                    parameters:
                    - name: id
                      in: path
                      required: true
                      schema:
                        type: string
                    get:
                      operationId: getPayment
                      responses:
                        "200": {}
                components:
                  schemas: {}
                """);

        // `summary` and a path-item-level `parameters` list sit beside the verbs, and a reader
        // that walked every key would emit two operations named after them.
        assertThat(spec.operations()).extracting(SdkSpec.SdkOperation::id).containsExactly("getPayment");
    }

    // ── The fail-safe ───────────────────────────────────────────────────────────────────

    @Test
    void aSchemaWithNoTypeAndNoRefIsReportedRatherThanGuessedAt() {
        SdkSpecReader reader = new SdkSpecReader();

        SdkSpec spec = reader.read(OpenApiYaml.read(MINIMAL_HEADER + """
                paths: {}
                components:
                  schemas:
                    Thing:
                      type: object
                      properties:
                        mystery:
                          description: Something with no shape at all.
                """));

        assertThat(model(spec, "Thing").fields().getFirst().type().kind()).isEqualTo(Kind.UNKNOWN);
        // Named, not counted. A finding that says "one field is undescribed" sends a reader to
        // grep a 7,000-line document; this one says which.
        assertThat(reader.unsupported()).singleElement()
                .asString().contains("Thing.mystery");
    }

    @Test
    void anUnsupportedTypeIsReported() {
        SdkSpecReader reader = new SdkSpecReader();

        reader.read(OpenApiYaml.read(MINIMAL_HEADER + """
                paths: {}
                components:
                  schemas:
                    Thing:
                      type: object
                      properties:
                        weird:
                          type: quaternion
                """));

        assertThat(reader.unsupported()).singleElement().asString().contains("quaternion");
    }

    @Test
    void anOperationWithNoIdIsReportedBecauseNothingCouldNameTheMethod() {
        SdkSpecReader reader = new SdkSpecReader();

        reader.read(OpenApiYaml.read(MINIMAL_HEADER + """
                paths:
                  /v1/payments:
                    get:
                      responses:
                        "200": {}
                components:
                  schemas: {}
                """));

        assertThat(reader.unsupported()).singleElement().asString()
                .contains("get /v1/payments").contains("operationId");
    }

    @Test
    void aCleanDocumentReportsNothing() {
        SdkSpecReader reader = new SdkSpecReader();

        reader.read(OpenApiYaml.read(MINIMAL_HEADER + """
                paths: {}
                components:
                  schemas:
                    Thing:
                      type: object
                      properties:
                        id:
                          type: string
                """));

        // The other direction. A findings list that was never populated would make every test
        // above pass for the wrong reason.
        assertThat(reader.unsupported()).isEmpty();
    }
}
