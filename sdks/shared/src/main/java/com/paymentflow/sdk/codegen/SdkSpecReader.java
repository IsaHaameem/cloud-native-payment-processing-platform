package com.paymentflow.sdk.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.paymentflow.sdk.codegen.SdkSpec.SdkEnum;
import com.paymentflow.sdk.codegen.SdkSpec.SdkField;
import com.paymentflow.sdk.codegen.SdkSpec.SdkModel;
import com.paymentflow.sdk.codegen.SdkSpec.SdkOperation;
import com.paymentflow.sdk.codegen.SdkSpec.SdkParameter;
import com.paymentflow.sdk.codegen.SdkSpec.SdkResponse;
import com.paymentflow.sdk.codegen.SdkSpec.SdkType;
import com.paymentflow.sdk.codegen.SdkSpec.SdkType.Kind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns {@code docs/openapi.yaml} into the {@link SdkSpec} both emitters read (M22.1).
 *
 * <p>This is the only place in the SDK toolchain that knows what OpenAPI looks like. Every
 * decision about the contract's meaning — what nullable is spelled as, which enums exist and
 * what they are called, which response is "the" success — is made here, once, so the two
 * languages cannot answer it differently.
 *
 * <p><b>It refuses rather than guesses.</b> A schema this reader has no rule for produces
 * {@link Kind#UNKNOWN} and is collected in {@link #unsupported()}; {@link SdkCodegen} turns a
 * non-empty list into a failed build. That is the same fail-safe direction {@code OpenApiDiff}
 * takes for the same reason (D158): the realistic failure of a generator is not a wrong rule
 * but an absent one, and a permissive fallback ships an SDK whose types quietly say
 * {@code unknown} about a field the platform documents precisely.
 */
public final class SdkSpecReader {

    /** Statuses that are the operation's documented success, in the order they are preferred. */
    private static final List<String> SUCCESS_STATUSES = List.of("200", "201", "202", "204");

    /** HTTP verbs a path item may carry. Anything else in a path item is not an operation. */
    private static final Set<String> VERBS = Set.of("get", "put", "post", "delete", "patch", "head", "options");

    private final List<String> unsupported = new ArrayList<>();

    /** Constructs this reader's findings alongside the spec; both are per-invocation state. */
    public List<String> unsupported() {
        return List.copyOf(unsupported);
    }

    public SdkSpec read(JsonNode document) {
        unsupported.clear();

        JsonNode schemas = document.path("components").path("schemas");

        // Enums are collected while the models are walked, because every one of them is
        // declared inline on a property — there is no separate section to read. A TreeMap so
        // the output order is the name's, not the document's, which keeps a regenerated file
        // diffable against the committed one.
        Map<String, SdkEnum> enums = new TreeMap<>();
        List<SdkModel> models = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : sorted(schemas)) {
            models.add(readModel(entry.getKey(), entry.getValue(), enums));
        }

        return new SdkSpec(
                text(document.path("info").path("version")),
                text(document.path("info").path("title")),
                text(document.path("servers").path(0).path("url")),
                List.copyOf(enums.values()),
                models,
                readOperations(document.path("paths")));
    }

    // ── Models ──────────────────────────────────────────────────────────────────────────

    private SdkModel readModel(String name, JsonNode schema, Map<String, SdkEnum> enums) {
        Set<String> required = new LinkedHashSet<>();
        schema.path("required").forEach(value -> required.add(value.asText()));

        List<SdkField> fields = new ArrayList<>();
        for (Map.Entry<String, JsonNode> property : sorted(schema.path("properties"))) {
            String field = property.getKey();
            JsonNode value = property.getValue();
            collectEnum(name, field, value, enums);
            fields.add(new SdkField(field, readType(value, name + "." + field),
                    required.contains(field), text(value.path("description"))));
        }
        return new SdkModel(name, text(schema.path("description")), fields);
    }

    /**
     * Names an inline enum after the model and field that declare it.
     *
     * <p>Two fields with the same value set — {@code mode} is {@code test}/{@code live} on
     * seven different models — deliberately produce seven aliases rather than one shared type.
     * Collapsing them would mean naming the survivor arbitrarily, and would couple two
     * resources that are only accidentally alike: the day one of them grows a third mode, the
     * shared alias would silently widen the other six.
     */
    private void collectEnum(String model, String field, JsonNode schema, Map<String, SdkEnum> enums) {
        JsonNode values = schema.path("enum");
        if (!values.isArray() || values.isEmpty()) {
            return;
        }
        List<String> literals = new ArrayList<>();
        values.forEach(value -> literals.add(value.asText()));
        String name = pascalCase(model) + pascalCase(field);
        enums.put(name, new SdkEnum(name, model, field, text(schema.path("description")), literals));
    }

    // ── Types ───────────────────────────────────────────────────────────────────────────

    /**
     * @param location where in the document this schema sits, used only to make an
     *                 {@link Kind#UNKNOWN} finding actionable — "a schema has no type" names
     *                 nothing a reader can go and look at.
     */
    private SdkType readType(JsonNode schema, String location) {
        String reference = schema.path("$ref").asText("");
        if (!reference.isEmpty()) {
            return SdkType.reference(componentName(reference), false);
        }

        // OpenAPI 3.1 spells nullability as a type union: `type: [string, "null"]`. M21.7
        // established that spelling deliberately (springdoc silently drops 3.0's `nullable`
        // flag in a 3.1 document), so it is the only form this reader needs to understand —
        // and the only form that appears in the baseline.
        JsonNode type = schema.path("type");
        boolean nullable = false;
        String primary = null;
        if (type.isArray()) {
            for (JsonNode candidate : type) {
                if ("null".equals(candidate.asText())) {
                    nullable = true;
                } else {
                    primary = candidate.asText();
                }
            }
        } else if (type.isTextual()) {
            primary = type.asText();
        }

        if (primary == null) {
            unsupported.add(location + ": no `type` and no `$ref`, so the field's shape is undescribed");
            return SdkType.of(Kind.UNKNOWN, nullable);
        }

        return switch (primary) {
            case "string" -> SdkType.of(stringKind(schema), nullable);
            case "integer" -> SdkType.of(Kind.INTEGER, nullable);
            case "number" -> SdkType.of(Kind.NUMBER, nullable);
            case "boolean" -> SdkType.of(Kind.BOOLEAN, nullable);
            case "array" -> SdkType.array(readType(schema.path("items"), location + "[]"), nullable);
            case "object" -> readObject(schema, location, nullable);
            default -> {
                unsupported.add(location + ": unsupported type `" + primary + "`");
                yield SdkType.of(Kind.UNKNOWN, nullable);
            }
        };
    }

    /**
     * {@code format} narrows a string to something a caller should not have to parse by hand.
     * An unrecognised format is not a finding — it is a hint, and ignoring one costs a caller
     * nothing but a slightly wider type.
     */
    private static Kind stringKind(JsonNode schema) {
        return switch (schema.path("format").asText("")) {
            case "date" -> Kind.DATE;
            case "date-time" -> Kind.DATE_TIME;
            case "uuid" -> Kind.UUID;
            default -> Kind.STRING;
        };
    }

    /**
     * An object is either a map ({@code additionalProperties} with a schema — {@code metadata}
     * everywhere) or deliberately free-form ({@code EventResponse.data}, which audit-service
     * stores verbatim as an opaque tree, D44).
     */
    private SdkType readObject(JsonNode schema, String location, boolean nullable) {
        JsonNode additional = schema.path("additionalProperties");
        if (additional.isObject()) {
            return SdkType.map(readType(additional, location + "{}"), nullable);
        }
        return SdkType.of(Kind.OBJECT, nullable);
    }

    // ── Operations ──────────────────────────────────────────────────────────────────────

    private List<SdkOperation> readOperations(JsonNode paths) {
        List<SdkOperation> operations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : sorted(paths)) {
            for (Map.Entry<String, JsonNode> verb : sorted(path.getValue())) {
                if (!VERBS.contains(verb.getKey())) {
                    continue;
                }
                operations.add(readOperation(path.getKey(), verb.getKey(), verb.getValue()));
            }
        }
        return operations;
    }

    private SdkOperation readOperation(String path, String verb, JsonNode operation) {
        String id = operation.path("operationId").asText("");
        if (id.isEmpty()) {
            unsupported.add(verb + " " + path + ": no operationId, so nothing can name the method");
        }

        List<SdkParameter> parameters = new ArrayList<>();
        operation.path("parameters").forEach(parameter -> parameters.add(new SdkParameter(
                parameter.path("name").asText(),
                parameter.path("in").asText(),
                parameter.path("required").asBoolean(false),
                readType(parameter.path("schema"), id + "." + parameter.path("name").asText()),
                text(parameter.path("description")))));

        return new SdkOperation(
                id,
                verb.toUpperCase(Locale.ROOT),
                path,
                operation.path("tags").path(0).asText(""),
                text(operation.path("summary")),
                parameters,
                bodyModel(operation.path("requestBody")),
                successResponse(operation.path("responses")));
    }

    private static String bodyModel(JsonNode requestBody) {
        String reference = requestBody.path("content").path("application/json").path("schema")
                .path("$ref").asText("");
        return reference.isEmpty() ? null : componentName(reference);
    }

    /**
     * The one response a caller gets when nothing went wrong. Every operation in this document
     * has exactly one 2xx; the loop is over the preference order rather than "the first 2xx"
     * so that an operation which later documents both a 200 and a 202 resolves to the same
     * answer on every run instead of to whichever the document happened to list first.
     */
    private SdkResponse successResponse(JsonNode responses) {
        for (String status : SUCCESS_STATUSES) {
            JsonNode response = responses.path(status);
            if (response.isMissingNode()) {
                continue;
            }
            String reference = response.path("content").path("application/json").path("schema")
                    .path("$ref").asText("");
            return new SdkResponse(status, reference.isEmpty() ? null : componentName(reference));
        }
        return null;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────

    private static String componentName(String reference) {
        return reference.substring(reference.lastIndexOf('/') + 1);
    }

    /** Sorted by key, so a regenerated file differs from the committed one only in substance. */
    private static List<Map.Entry<String, JsonNode>> sorted(JsonNode object) {
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        if (object.isObject()) {
            object.properties().forEach(entries::add);
        }
        entries.sort(Map.Entry.comparingByKey());
        return entries;
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    /** {@code payment_method} / {@code statusCode} / {@code type} all become {@code PascalCase}. */
    static String pascalCase(String value) {
        StringBuilder result = new StringBuilder();
        boolean upper = true;
        for (char character : value.toCharArray()) {
            if (character == '_' || character == '-' || character == '.' || character == ' ') {
                upper = true;
            } else if (upper) {
                result.append(Character.toUpperCase(character));
                upper = false;
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }
}
