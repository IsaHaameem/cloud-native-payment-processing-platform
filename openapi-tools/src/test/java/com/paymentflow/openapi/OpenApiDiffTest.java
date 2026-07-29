package com.paymentflow.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The breaking-change classifier (M21.6, §5/M21 task 5).
 *
 * <p>§5/M21's risk table names this suite as part of the deliverable, and says why: *"the
 * breaking-change classifier has false negatives"* is the risk, and a gate nobody has seen
 * be wrong is not known to be right. Each test below is one rule, written as the edit a
 * developer would actually make — a field removed, a parameter made required, a description
 * added — rather than as a synthetic tree, because the rules only earn their place if they
 * fire on real changes.
 *
 * <p>Fixtures are built by <em>mutating the parsed document</em> rather than by rewriting
 * its text. The first draft did the latter and eight tests passed a document that had not
 * actually changed: a `replace` that silently matched nothing produced an empty diff, which
 * is indistinguishable from "no breaking changes found". A test for a gate must not be able
 * to pass by failing to make the change it claims to make.
 *
 * <p>The two that matter most are at the end of the file: the one proving that M21.7's
 * several hundred added descriptions are <em>not</em> breaking, and the one proving that a
 * keyword this diff has never heard of is.
 */
class OpenApiDiffTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OpenApiDiff diff = new OpenApiDiff();

    /** A minimal but structurally real document, used as the "previous" side throughout. */
    private static final String BASE = """
            {
              "openapi": "3.1.0",
              "info": {"title": "PaymentFlow API", "version": "2026-08-01"},
              "servers": [{"url": "https://api.paymentflow.dev"}],
              "security": [{"SecretKey": []}],
              "paths": {
                "/v1/payments": {
                  "get": {
                    "operationId": "listPayments",
                    "parameters": [
                      {"in": "query", "name": "limit", "required": false, "schema": {"type": "integer"}}
                    ],
                    "responses": {
                      "200": {
                        "description": "OK",
                        "content": {"application/json": {"schema": {"$ref": "#/components/schemas/PaymentResponse"}}}
                      }
                    }
                  },
                  "post": {
                    "operationId": "createPayment",
                    "requestBody": {
                      "required": true,
                      "content": {"application/json": {"schema": {"$ref": "#/components/schemas/CreatePaymentRequest"}}}
                    },
                    "responses": {
                      "201": {
                        "description": "Created",
                        "content": {"application/json": {"schema": {"$ref": "#/components/schemas/PaymentResponse"}}}
                      }
                    }
                  }
                }
              },
              "components": {
                "schemas": {
                  "PaymentResponse": {
                    "type": "object",
                    "properties": {
                      "id": {"type": "string", "format": "uuid"},
                      "amountMinor": {"type": "integer", "format": "int64"},
                      "status": {"type": "string", "enum": ["succeeded", "failed"]}
                    }
                  },
                  "CreatePaymentRequest": {
                    "type": "object",
                    "required": ["amountMinor"],
                    "properties": {
                      "amountMinor": {"type": "integer", "format": "int64"},
                      "description": {"type": "string", "maxLength": 500}
                    }
                  }
                },
                "securitySchemes": {"SecretKey": {"type": "http", "scheme": "bearer"}}
              },
              "tags": [{"name": "Payments", "description": "Payments."}]
            }""";

    // ── Nothing changed ─────────────────────────────────────────────────────────────────

    @Test
    void anUnchangedDocumentProducesNoFindingsAtAll() {
        OpenApiDiff.Result result = diff.compare(base(), base());

        assertThat(result.changes()).isEmpty();
        assertThat(result.isAcceptable()).isTrue();
        // The gate runs on every commit and most commits do not touch the API. If an
        // unchanged document produced findings, the report would be noise within a week.
        assertThat(result.revisionDeclared()).isFalse();
    }

    // ── Paths and operations ────────────────────────────────────────────────────────────

    @Test
    void removingAPathIsBreakingAndAddingOneIsNot() {
        OpenApiDiff.Result result = compare(document -> {
            ObjectNode paths = object(document, "paths");
            paths.set("/v1/charges", paths.remove("/v1/payments"));
        });

        // Renaming reads as one removal plus one addition, which is exactly what it is for
        // a client: the endpoint they call is gone, and a different one exists.
        assertThat(breakingLocations(result)).contains("paths./v1/payments");
        assertThat(additiveLocations(result)).contains("paths./v1/charges");
        assertThat(result.isAcceptable()).isFalse();
    }

    @Test
    void removingAnOperationFromAPathIsBreaking() {
        OpenApiDiff.Result result = compare(document -> object(document, "paths", "/v1/payments").remove("post"));

        assertThat(breakingLocations(result)).containsExactly("paths./v1/payments.post");
        assertThat(result.breaking().getFirst().detail()).contains("405");
    }

    @Test
    void renamingAnOperationIdIsBreakingBecauseItRenamesEverySdkMethod() {
        OpenApiDiff.Result result = compare(document ->
                object(document, "paths", "/v1/payments", "get").put("operationId", "getPayments"));

        // Not one byte of the wire contract changed. Every SDK generated from the previous
        // document still has a method that no longer exists after regenerating.
        assertThat(breakingLocations(result)).containsExactly("paths./v1/payments.get.operationId");
    }

    // ── Parameters ──────────────────────────────────────────────────────────────────────

    @Test
    void aNewOptionalParameterIsAdditiveAndANewRequiredOneIsNot() {
        OpenApiDiff.Result optional = compare(document ->
                parameters(document).add(queryParameter("status", false)));
        assertThat(optional.breaking()).isEmpty();
        assertThat(additiveLocations(optional)).containsExactly("paths./v1/payments.get.parameters.query:status");

        OpenApiDiff.Result required = compare(document ->
                parameters(document).add(queryParameter("mode", true)));
        assertThat(breakingLocations(required)).containsExactly("paths./v1/payments.get.parameters.query:mode");
        assertThat(required.breaking().getFirst().detail()).contains("every existing call omits it");
    }

    @Test
    void makingAnExistingParameterRequiredIsBreaking() {
        OpenApiDiff.Result result = compare(document ->
                ((ObjectNode) parameters(document).get(0)).put("required", true));

        assertThat(breakingLocations(result)).containsExactly("paths./v1/payments.get.parameters.query:limit");
        assertThat(result.breaking().getFirst().detail()).contains("every call that omitted it now fails");
    }

    @Test
    void removingAParameterIsBreakingBecauseTheClientIsNowSilentlyIgnored() {
        OpenApiDiff.Result result = compare(document -> parameters(document).remove(0));

        assertThat(breakingLocations(result)).containsExactly("paths./v1/payments.get.parameters.query:limit");
        assertThat(result.breaking().getFirst().detail()).contains("silently ignored");
    }

    @Test
    void changingHowAParameterIsSpelledOnTheWireIsBreaking() {
        // deepObject + explode is `metadata[key]=value` (D142). A parameter that keeps its
        // name and changes its style is a different query string under the same label.
        OpenApiDiff.Result result = compare(document ->
                ((ObjectNode) parameters(document).get(0)).put("style", "form"));

        assertThat(breakingLocations(result)).containsExactly("paths./v1/payments.get.parameters.query:limit.style");
    }

    // ── Request bodies ──────────────────────────────────────────────────────────────────

    @Test
    void makingTheRequestBodyRequiredIsBreaking() {
        JsonNode optionalBody = mutate(base(), document ->
                object(document, "paths", "/v1/payments", "post", "requestBody").put("required", false));

        OpenApiDiff.Result result = diff.compare(optionalBody, base());

        assertThat(breakingLocations(result)).containsExactly("paths./v1/payments.post.requestBody");
    }

    // ── Response bodies and schemas ─────────────────────────────────────────────────────

    @Test
    void removingAFieldFromAResponseIsBreakingAndAddingOneIsNot() {
        OpenApiDiff.Result removed = compare(document ->
                properties(document, "PaymentResponse").remove("amountMinor"));
        assertThat(breakingLocations(removed))
                .containsExactly("components.schemas.PaymentResponse.properties.amountMinor");
        assertThat(removed.breaking().getFirst().detail()).contains("code reading it now finds nothing");

        OpenApiDiff.Result added = compare(document ->
                properties(document, "PaymentResponse").set("receiptUrl", JSON.createObjectNode().put("type", "string")));
        assertThat(added.breaking()).isEmpty();
        assertThat(additiveLocations(added))
                .containsExactly("components.schemas.PaymentResponse.properties.receiptUrl");
    }

    @Test
    void makingAFieldRequiredIsBreakingAndRelaxingItIsNot() {
        OpenApiDiff.Result tightened = compare(document ->
                ((ArrayNode) object(document, "components", "schemas", "CreatePaymentRequest").path("required"))
                        .add("description"));
        assertThat(breakingLocations(tightened)).containsExactly("components.schemas.CreatePaymentRequest.required");
        assertThat(tightened.breaking().getFirst().detail()).contains("`description` became required");

        OpenApiDiff.Result relaxed = compare(document ->
                object(document, "components", "schemas", "CreatePaymentRequest").remove("required"));
        assertThat(relaxed.breaking()).isEmpty();
        assertThat(additiveLocations(relaxed)).containsExactly("components.schemas.CreatePaymentRequest.required");
    }

    @Test
    void changingAFieldsTypeIsBreaking() {
        // The money rule made concrete: amountMinor is an integer in minor units
        // (invariant 7), and a client that starts receiving a string does not merely
        // mis-render it, it fails to parse the response.
        OpenApiDiff.Result result = compare(document ->
                ((ObjectNode) properties(document, "PaymentResponse").path("amountMinor"))
                        .put("type", "string").remove("format"));

        assertThat(breakingLocations(result))
                .contains("components.schemas.PaymentResponse.properties.amountMinor.type");
    }

    @Test
    void addingAnEnumValueIsAdditiveByPolicyAndRemovingOneIsBreaking() {
        // §9 requires clients to tolerate unknown enum values, which is precisely what makes
        // shipping a new payment status without a revision safe. That is a policy, not an
        // inference from the document, so it is asserted rather than assumed.
        OpenApiDiff.Result added = compare(document ->
                ((ArrayNode) properties(document, "PaymentResponse").path("status").path("enum"))
                        .add("requires_action"));
        assertThat(added.breaking()).isEmpty();
        assertThat(added.additive()).singleElement()
                .satisfies(change -> assertThat(change.detail()).contains("requires_action"));

        OpenApiDiff.Result removed = compare(document ->
                ((ArrayNode) properties(document, "PaymentResponse").path("status").path("enum")).remove(1));
        assertThat(removed.breaking()).singleElement()
                .satisfies(change -> assertThat(change.detail()).contains("`failed`"));
    }

    @Test
    void tighteningAConstraintIsBreakingAndLooseningItIsNot() {
        OpenApiDiff.Result tightened = compare(document ->
                ((ObjectNode) properties(document, "CreatePaymentRequest").path("description")).put("maxLength", 100));
        assertThat(breakingLocations(tightened))
                .containsExactly("components.schemas.CreatePaymentRequest.properties.description.maxLength");

        OpenApiDiff.Result loosened = compare(document ->
                ((ObjectNode) properties(document, "CreatePaymentRequest").path("description")).put("maxLength", 5000));
        assertThat(loosened.breaking()).isEmpty();
    }

    @Test
    void introducingABoundWhereThereWasNoneIsBreaking() {
        OpenApiDiff.Result result = compare(document ->
                ((ObjectNode) properties(document, "PaymentResponse").path("id")).put("maxLength", 36));

        assertThat(breakingLocations(result))
                .containsExactly("components.schemas.PaymentResponse.properties.id.maxLength");
        assertThat(result.breaking().getFirst().detail()).contains("unbounded to 36");
    }

    @Test
    void changingWhatAnOperationReturnsIsBreaking() {
        OpenApiDiff.Result result = compare(document ->
                object(document, "paths", "/v1/payments", "get", "responses", "200",
                        "content", "application/json", "schema")
                        .put("$ref", "#/components/schemas/CreatePaymentRequest"));

        assertThat(breakingLocations(result))
                .containsExactly("paths./v1/payments.get.responses.200.content.application/json.schema.$ref");
    }

    @Test
    void removingADocumentedResponseIsBreakingEvenWhenItIsAnError() {
        // An SDK generated from the previous document has a typed case for that response,
        // and regenerating deletes it — a source-level break even though the wire behaviour
        // is unchanged.
        JsonNode withNotFound = mutate(base(), document ->
                object(document, "paths", "/v1/payments", "get", "responses")
                        .set("404", JSON.createObjectNode().put("description", "Not found.")));

        assertThat(breakingLocations(diff.compare(withNotFound, base())))
                .containsExactly("paths./v1/payments.get.responses.404");
        // ...and documenting a response that was not documented before is additive.
        assertThat(diff.compare(base(), withNotFound).breaking()).isEmpty();
    }

    @Test
    void droppingSupportForAMediaTypeIsBreaking() {
        OpenApiDiff.Result result = compare(document -> {
            ObjectNode content = object(document, "paths", "/v1/payments", "get", "responses", "200", "content");
            content.set("application/xml", content.remove("application/json"));
        });

        assertThat(breakingLocations(result))
                .containsExactly("paths./v1/payments.get.responses.200.content.application/json");
    }

    // ── Document level ──────────────────────────────────────────────────────────────────

    @Test
    void movingTheServerIsBreakingEvenThoughNoShapeChanged() {
        OpenApiDiff.Result result = compare(document ->
                ((ObjectNode) document.path("servers").get(0)).put("url", "https://api.paymentflow.io"));

        assertThat(breakingLocations(result)).containsExactly("servers");
    }

    @Test
    void requiringACredentialWhereNoneWasRequiredIsBreaking() {
        // The one endpoint this protects is GET /v1/test/cards (§8.1), the platform's only
        // genuinely unauthenticated public endpoint.
        JsonNode anonymous = mutate(base(), document ->
                object(document, "paths", "/v1/payments", "get").set("security", JSON.createArrayNode()));

        assertThat(breakingLocations(diff.compare(anonymous, base())))
                .containsExactly("paths./v1/payments.get.security");
        assertThat(diff.compare(base(), anonymous).breaking()).isEmpty();
    }

    @Test
    void removingASharedComponentIsBreakingBecauseEveryRefToItDangles() {
        OpenApiDiff.Result result = compare(document ->
                object(document, "components", "securitySchemes").remove("SecretKey"));

        assertThat(breakingLocations(result)).containsExactly("components.securitySchemes.SecretKey");
    }

    @Test
    void redefiningASharedComponentIsBreakingForEveryReferenceAtOnce() {
        OpenApiDiff.Result result = compare(document ->
                object(document, "components", "securitySchemes", "SecretKey").put("scheme", "basic"));

        assertThat(breakingLocations(result)).containsExactly("components.securitySchemes.SecretKey");
    }

    // ── The declaration ─────────────────────────────────────────────────────────────────

    @Test
    void aBreakingChangeIsAcceptableOnceANewRevisionDeclaresIt() {
        OpenApiDiff.Result result = compare(document -> {
            object(document, "info").put("version", "2026-12-01");
            properties(document, "PaymentResponse").remove("amountMinor");
        });

        // The gate does not forbid breaking the API. It forbids breaking it silently.
        assertThat(result.breaking()).isNotEmpty();
        assertThat(result.revisionDeclared()).isTrue();
        assertThat(result.isAcceptable()).isTrue();
    }

    @Test
    void aRevisionThatMovedBackwardsDoesNotDeclareAnything() {
        OpenApiDiff.Result result = compare(document -> {
            object(document, "info").put("version", "2026-07-27");
            properties(document, "PaymentResponse").remove("amountMinor");
        });

        // Dated revisions only ever move forward. Accepting one that went backwards would
        // turn the gate into a formality anyone could satisfy by editing a string.
        assertThat(result.revisionDeclared()).isFalse();
        assertThat(result.isAcceptable()).isFalse();
    }

    @Test
    void theRevisionIsNotItselfReportedAsAChange() {
        OpenApiDiff.Result result = compare(document -> object(document, "info").put("version", "2026-12-01"));

        // Cutting a revision is the declaration the report is judged against, not an edit
        // within it. Listing it would invite reading "1 change" as though the version bump
        // were the thing that changed the contract.
        assertThat(result.changes()).isEmpty();
        assertThat(result.revisionDeclared()).isTrue();
    }

    // ── The two that guard the gate itself ──────────────────────────────────────────────

    @Test
    void writingDocumentationIsNeverABreakingChange() {
        // This is M21.7 in miniature: an operation summary, a field description, an example,
        // and a reworded tag. If prose classified as breaking, the documentation milestone
        // could not ship without cutting a revision that changes nothing on the wire — and
        // the gate would have been switched off to allow it.
        OpenApiDiff.Result result = compare(document -> {
            object(document, "paths", "/v1/payments", "get")
                    .put("summary", "List payments")
                    .put("description", "Returns a cursor-paginated list of payments.");
            ((ObjectNode) properties(document, "PaymentResponse").path("id"))
                    .put("description", "The payment's unique identifier.")
                    .put("example", "9f2c1e7a-4b8d-4c3e-8a1d-2b4f6a8c05d1");
            object(document, "paths", "/v1/payments", "get", "responses", "200")
                    .put("description", "A page of payments, most recent first.");
            ((ObjectNode) document.path("tags").get(0))
                    .put("description", "Create, authorize, capture, refund and void payments.");
        });

        assertThat(result.breaking()).isEmpty();
        assertThat(result.changes()).isEmpty();
        assertThat(result.isAcceptable()).isTrue();
    }

    @Test
    void aKeywordThisDiffHasNoRuleForIsTreatedAsBreaking() {
        // The realistic way this gate fails is not a wrong rule but a missing one: springdoc
        // emits something nobody anticipated, no walker looks at it, and the gate reports
        // "no breaking changes" about a document that lost a field. Defaulting to breaking
        // costs one conversation; defaulting to safe ships a broken contract.
        OpenApiDiff.Result result = compare(document ->
                ((ObjectNode) properties(document, "PaymentResponse").path("id"))
                        .put("unevaluatedProperties", false));

        assertThat(result.breaking()).singleElement().satisfies(change -> {
            assertThat(change.location())
                    .isEqualTo("components.schemas.PaymentResponse.properties.id.unevaluatedProperties");
            assertThat(change.detail()).contains("no rule for it");
        });
    }

    @Test
    void breakingChangesAreReportedBeforeAdditiveOnes() {
        OpenApiDiff.Result result = compare(document -> {
            properties(document, "PaymentResponse").remove("amountMinor");
            object(document, "paths").set("/v1/disputes", JSON.createObjectNode());
        });

        // A developer reading a 40-line report acts on the first screen. Everything else in
        // it is context.
        assertThat(result.changes()).hasSize(2);
        assertThat(result.changes().getFirst().isBreaking()).isTrue();
        assertThat(result.changes().getLast().isBreaking()).isFalse();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────

    /** Compares a mutated copy of {@link #BASE} against the original. */
    private OpenApiDiff.Result compare(Consumer<ObjectNode> mutation) {
        return diff.compare(base(), mutate(base(), mutation));
    }

    private static JsonNode base() {
        return parse(BASE);
    }

    private static JsonNode mutate(JsonNode document, Consumer<ObjectNode> mutation) {
        ObjectNode copy = (ObjectNode) document.deepCopy();
        mutation.accept(copy);
        return copy;
    }

    /**
     * Navigates to an object node, failing loudly if the path does not exist — the guard
     * against a fixture that quietly mutates nothing, which is how the first draft of this
     * suite produced eight false passes.
     */
    private static ObjectNode object(JsonNode root, String... path) {
        JsonNode node = root;
        for (String segment : path) {
            node = node.path(segment);
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("no object at " + String.join(".", path));
        }
        return (ObjectNode) node;
    }

    private static ObjectNode properties(JsonNode document, String schema) {
        return object(document, "components", "schemas", schema, "properties");
    }

    private static ArrayNode parameters(JsonNode document) {
        JsonNode node = object(document, "paths", "/v1/payments", "get").path("parameters");
        if (!node.isArray()) {
            throw new IllegalArgumentException("the fixture's parameter list is missing");
        }
        return (ArrayNode) node;
    }

    private static ObjectNode queryParameter(String name, boolean required) {
        ObjectNode parameter = JSON.createObjectNode()
                .put("in", "query")
                .put("name", name)
                .put("required", required);
        parameter.putObject("schema").put("type", "string");
        return parameter;
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("the test fixture is not valid JSON: " + e.getMessage(), e);
        }
    }

    private static List<String> breakingLocations(OpenApiDiff.Result result) {
        return result.breaking().stream().map(OpenApiChange::location).toList();
    }

    private static List<String> additiveLocations(OpenApiDiff.Result result) {
        return result.additive().stream().map(OpenApiChange::location).toList();
    }
}
