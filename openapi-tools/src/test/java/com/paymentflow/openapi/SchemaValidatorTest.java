package com.paymentflow.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The validator behind M21.7's contract tests.
 *
 * <p>Unit tests over hand-written schemas rather than assertions about the real document,
 * for the same reason {@code OpenApiMergerTest} works that way: the real document and the
 * real responses agree — that is what the contract tests are for — so a suite built on them
 * would exercise only the happy path and keep passing right up until the day it mattered.
 * What needs proving is the behaviour on payloads that <em>do not</em> match, and the only
 * way to have those is to write them.
 *
 * <p>Two properties are deliberately non-standard and are asserted here because they are the
 * reason this class exists rather than a library: an object schema with {@code properties}
 * and no {@code additionalProperties} is treated as <b>closed</b>, and a keyword the
 * validator does not implement is a <b>violation</b> rather than a silent pass.
 */
class SchemaValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SchemaValidator validator = new SchemaValidator(document());

    private static JsonNode document() {
        return parse("""
                {"components": {"schemas": {
                  "Money": {"type": "object", "properties": {
                    "amountMinor": {"type": "integer", "format": "int64"},
                    "currency": {"type": "string"}}},
                  "Payment": {"type": "object", "required": ["id"], "properties": {
                    "id": {"type": "string", "format": "uuid"},
                    "status": {"type": "string", "enum": ["succeeded", "failed"]},
                    "money": {"$ref": "#/components/schemas/Money"},
                    "tags": {"type": "array", "items": {"type": "string"}},
                    "metadata": {"type": "object", "additionalProperties": {"type": "string"}},
                    "payload": {"type": "object"},
                    "note": {"type": ["string", "null"]}}}}}}""");
    }

    // ── The happy path ──────────────────────────────────────────────────────────────────

    @Test
    void aResponseThatMatchesItsSchemaProducesNoViolations() {
        List<String> violations = validate("""
                {"id": "9f2c1e7a-4b8d-4c3e-8a1d-2b4f6a8c05d1", "status": "succeeded",
                 "money": {"amountMinor": 1000, "currency": "USD"},
                 "tags": ["a", "b"], "metadata": {"orderId": "A-1"},
                 "payload": {"anything": [1, 2, {"nested": true}]}, "note": null}""");

        assertThat(violations).isEmpty();
    }

    // ── Types ───────────────────────────────────────────────────────────────────────────

    @Test
    void aFieldOfTheWrongTypeIsReportedWithItsPath() {
        List<String> violations = validate("""
                {"id": "9f2c1e7a-4b8d-4c3e-8a1d-2b4f6a8c05d1",
                 "money": {"amountMinor": "1000", "currency": "USD"}}""");

        // The money rule made concrete: a string where an integer minor unit belongs is not
        // a rendering nuisance, it is a client that fails to parse the response.
        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation).startsWith("money.amountMinor:");
            assertThat(violation).contains("expected integer");
        });
    }

    @Test
    void aDecimalIsNotAnInteger() {
        // `5` and `5.0` are different JSON, and only the first satisfies `type: integer`.
        assertThat(validate("""
                {"id": "9f2c1e7a-4b8d-4c3e-8a1d-2b4f6a8c05d1", "money": {"amountMinor": 10.5}}"""))
                .anyMatch(violation -> violation.contains("money.amountMinor"));
    }

    @Test
    void aNullableFieldAcceptsBothNullAndAValue() {
        // OpenAPI 3.1 spells nullability as a type union, and analytics-service depends on
        // it: `successRate` is explicitly null when nothing was attempted.
        assertThat(validate("{\"id\": \"9f2c1e7a-4b8d-4c3e-8a1d-2b4f6a8c05d1\", \"note\": null}")).isEmpty();
        assertThat(validate("{\"id\": \"9f2c1e7a-4b8d-4c3e-8a1d-2b4f6a8c05d1\", \"note\": \"hi\"}")).isEmpty();
    }

    @Test
    void aMalformedFormattedValueIsReported() {
        assertThat(validate("{\"id\": \"not-a-uuid\"}"))
                .singleElement()
                .satisfies(violation -> assertThat(violation).contains("is not a valid uuid"));
    }

    // ── Structure ───────────────────────────────────────────────────────────────────────

    @Test
    void aMissingRequiredFieldIsReported() {
        assertThat(validate("{\"status\": \"succeeded\"}"))
                .singleElement()
                .satisfies(violation -> assertThat(violation).contains("required field `id` is absent"));
    }

    @Test
    void anUndocumentedFieldIsAViolationEvenThoughJsonSchemaWouldAllowIt() {
        // The rule this class exists for. Under permissive rules a response could gain five
        // undocumented fields and validate perfectly, which is precisely the drift §5/M21
        // task 6 asks to be caught.
        assertThat(validate("""
                {"id": "9f2c1e7a-4b8d-4c3e-8a1d-2b4f6a8c05d1", "refundedAmountMinor": 500}"""))
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation).startsWith("refundedAmountMinor:");
                    assertThat(violation).contains("the document does not describe");
                });
    }

    @Test
    void anUntypedObjectAcceptsAnythingRatherThanNothing() {
        // audit-service's event payload (D44). A schema with neither `properties` nor
        // `additionalProperties` is an open object on purpose, and treating it as closed
        // would fail every real event.
        assertThat(validate("""
                {"id": "9f2c1e7a-4b8d-4c3e-8a1d-2b4f6a8c05d1",
                 "payload": {"paymentId": "x", "amountMinor": 5000, "nested": {"deep": [1, 2]}}}"""))
                .isEmpty();
    }

    @Test
    void aMapWithTheWrongValueTypeIsReported() {
        assertThat(validate("""
                {"id": "9f2c1e7a-4b8d-4c3e-8a1d-2b4f6a8c05d1", "metadata": {"orderId": 17}}"""))
                .singleElement()
                .satisfies(violation -> assertThat(violation).startsWith("metadata.orderId:"));
    }

    @Test
    void everyArrayElementIsCheckedAndReportedByIndex() {
        assertThat(validate("""
                {"id": "9f2c1e7a-4b8d-4c3e-8a1d-2b4f6a8c05d1", "tags": ["a", 2, "c", 4]}"""))
                .hasSize(2)
                .anyMatch(violation -> violation.startsWith("tags[1]:"))
                .anyMatch(violation -> violation.startsWith("tags[3]:"));
    }

    @Test
    void aReferencedSchemaIsFollowedRatherThanAccepted() {
        assertThat(validate("""
                {"id": "9f2c1e7a-4b8d-4c3e-8a1d-2b4f6a8c05d1",
                 "money": {"amountMinor": 1000, "currency": "USD", "surcharge": 5}}"""))
                .singleElement()
                .satisfies(violation -> assertThat(violation).startsWith("money.surcharge:"));
    }

    @Test
    void aValueOutsideItsEnumerationIsReported() {
        assertThat(validate("""
                {"id": "9f2c1e7a-4b8d-4c3e-8a1d-2b4f6a8c05d1", "status": "AUTHORIZED"}"""))
                .singleElement()
                .satisfies(violation -> assertThat(violation).contains("not one of the documented values"));
    }

    @Test
    void everyViolationIsReportedRatherThanOnlyTheFirst() {
        // A response whose shape drifted usually drifted in several fields at once, and
        // fixing them one build at a time is how a contract test becomes something people
        // delete.
        assertThat(validate("""
                {"status": "unknown", "money": {"amountMinor": "x"}, "tags": [1]}"""))
                .hasSizeGreaterThanOrEqualTo(4);
    }

    // ── The fail-safe ───────────────────────────────────────────────────────────────────

    @Test
    void aKeywordThisValidatorDoesNotImplementIsAViolationRatherThanASilentPass() {
        SchemaValidator unknown = new SchemaValidator(parse("{}"));
        List<String> violations = unknown.validate(parse("{\"a\": 1}"),
                parse("""
                        {"type": "object", "unevaluatedProperties": false,
                         "properties": {"a": {"type": "integer"}}}"""), "");

        // A validator that quietly ignores what it does not understand reports success about
        // responses it never actually checked.
        assertThat(violations).singleElement()
                .satisfies(violation -> assertThat(violation).contains("does not implement"));
    }

    @Test
    void documentationKeywordsAreIgnoredRatherThanReportedAsUnsupported() {
        // M21.7 adds a description and an example to every field. If those counted as
        // unimplemented keywords, the documentation milestone would fail every contract test
        // it wrote.
        SchemaValidator documented = new SchemaValidator(parse("{}"));
        assertThat(documented.validate(parse("\"hello\""),
                parse("""
                        {"type": "string", "description": "A greeting.", "example": "hi",
                         "title": "Greeting", "deprecated": false, "readOnly": true}"""), ""))
                .isEmpty();
    }

    @Test
    void aDanglingReferenceIsReportedRatherThanTreatedAsPermissive() {
        SchemaValidator dangling = new SchemaValidator(parse("{\"components\": {\"schemas\": {}}}"));

        assertThat(dangling.validate(parse("{}"),
                parse("{\"$ref\": \"#/components/schemas/Missing\"}"), "body"))
                .singleElement()
                .satisfies(violation -> assertThat(violation).contains("does not resolve"));
    }

    private List<String> validate(String body) {
        return validator.validate(parse(body),
                parse("{\"$ref\": \"#/components/schemas/Payment\"}"), "");
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("the test fixture is not valid JSON: " + e.getMessage(), e);
        }
    }
}
