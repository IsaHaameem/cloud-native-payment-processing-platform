package com.paymentflow.openapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates a JSON value against one schema from the published OpenAPI document (M21.7,
 * §5/M21 task 6).
 *
 * <p><b>Why this exists rather than a JSON-Schema library.</b> The job is narrower and
 * stricter than general validation. Narrower, because the only schemas it ever sees are the
 * ones springdoc generates from this platform's DTOs — a small, known subset of JSON Schema
 * 2020-12 with no {@code $dynamicRef}, no remote references and no recursion outside named
 * components. Stricter, because of the rule below: a general validator is right to accept
 * fields a schema does not mention, and a <em>contract</em> test must not.
 *
 * <p><b>Closed objects, deliberately.</b> JSON Schema says an object schema with
 * {@code properties} and no {@code additionalProperties} accepts extra fields. This class
 * reports them. That is not a bug in the implementation of the specification, it is the
 * point of the exercise: §5/M21 task 6 asks for validation *"so the spec cannot silently
 * drift from the implementation"*, and every drift of that kind starts as a field the code
 * returns and the document does not mention. Under permissive rules a response could gain
 * five undocumented fields and validate perfectly.
 *
 * <p><b>An unimplemented keyword is a violation, not a silent pass.</b> Same reasoning as
 * {@link OpenApiDiff}: the realistic failure of a checker is a rule that was never written,
 * and a validator that quietly ignores what it does not understand reports success about
 * responses it never actually checked. {@code format} is the one deliberate exception —
 * JSON Schema 2020-12 defines it as an annotation, so an unrecognised format is ignored by
 * specification rather than by omission, and the ones this platform actually publishes are
 * checked.
 */
public final class SchemaValidator {

    /**
     * Keywords that carry documentation or presentation rather than constraints. Ignored
     * because there is nothing to check, not because they are unsupported.
     */
    private static final Set<String> ANNOTATIONS = Set.of(
            "description", "title", "example", "examples", "default", "deprecated",
            "readOnly", "writeOnly", "xml", "externalDocs", "discriminator", "$comment", "contentMediaType");

    /** Every keyword this class actually enforces. Anything outside both sets is a violation. */
    private static final Set<String> SUPPORTED = Set.of(
            "$ref", "type", "format", "properties", "required", "additionalProperties",
            "items", "enum", "const", "maxLength", "minLength", "pattern",
            "maximum", "minimum", "exclusiveMaximum", "exclusiveMinimum",
            "maxItems", "minItems", "uniqueItems", "allOf", "anyOf", "oneOf", "nullable");

    /** The document the schemas' {@code $ref}s resolve against. */
    private final JsonNode document;

    public SchemaValidator(JsonNode document) {
        this.document = document;
    }

    /**
     * @param location a human-readable path into the value being checked, so a violation
     *                 says {@code data[0].amountMinor} rather than "a field somewhere was
     *                 the wrong type".
     * @return every violation found, empty when the value satisfies the schema. All of them,
     *         not the first: a response whose shape drifted usually drifted in several
     *         fields, and fixing them one test run at a time is how a contract test becomes
     *         something people delete.
     */
    public List<String> validate(JsonNode value, JsonNode schema, String location) {
        List<String> violations = new ArrayList<>();
        validate(value, schema, location, violations);
        return violations;
    }

    private void validate(JsonNode value, JsonNode schema, String location, List<String> violations) {
        if (schema.isMissingNode() || schema.isEmpty()) {
            // `{}` accepts anything, and so does a response with no schema at all.
            return;
        }

        JsonNode resolved = resolve(schema, location, violations);
        if (resolved == null) {
            return;
        }
        if (resolved != schema) {
            validate(value, resolved, location, violations);
            return;
        }

        reportUnsupportedKeywords(schema, location, violations);

        if (!checkType(value, schema, location, violations)) {
            // Everything below assumes the value is the kind of thing the schema says it
            // is. Checking a string's `properties` after already reporting that it should
            // have been an object produces noise, not information.
            return;
        }

        checkEnum(value, schema, location, violations);
        checkStringConstraints(value, schema, location, violations);
        checkNumberConstraints(value, schema, location, violations);
        checkArray(value, schema, location, violations);
        checkObject(value, schema, location, violations);
        checkComposition(value, schema, location, violations);
    }

    /**
     * Follows a {@code $ref} to a named component. Only local references are supported —
     * this platform's document has no others, and a remote one would make a contract test
     * depend on the network.
     */
    private JsonNode resolve(JsonNode schema, String location, List<String> violations) {
        JsonNode ref = schema.path("$ref");
        if (ref.isMissingNode()) {
            return schema;
        }
        String pointer = ref.asText();
        if (!pointer.startsWith("#/")) {
            violations.add("%s: the document uses a non-local $ref (%s), which this validator does not follow"
                    .formatted(location, pointer));
            return null;
        }
        JsonNode target = document.at(pointer.substring(1));
        if (target.isMissingNode()) {
            violations.add("%s: $ref %s does not resolve - the document references a component it does not define"
                    .formatted(location, pointer));
            return null;
        }
        return target;
    }

    private void reportUnsupportedKeywords(JsonNode schema, String location, List<String> violations) {
        schema.properties().forEach(entry -> {
            String keyword = entry.getKey();
            if (!SUPPORTED.contains(keyword) && !ANNOTATIONS.contains(keyword)) {
                violations.add("%s: the document uses the keyword `%s`, which this validator does not implement - the value below it was not checked"
                        .formatted(location, keyword));
            }
        });
    }

    // ── type ────────────────────────────────────────────────────────────────────────────

    /**
     * OpenAPI 3.1 spells nullability as a type union ({@code type: [string, "null"]}) rather
     * than 3.0's {@code nullable: true}. Both are accepted here: the document is generated,
     * and which of the two springdoc emits is its decision to change.
     */
    private boolean checkType(JsonNode value, JsonNode schema, String location, List<String> violations) {
        JsonNode type = schema.path("type");
        if (type.isMissingNode()) {
            return true;
        }
        List<String> allowed = new ArrayList<>();
        if (type.isArray()) {
            type.forEach(entry -> allowed.add(entry.asText()));
        } else {
            allowed.add(type.asText());
        }
        if (schema.path("nullable").asBoolean(false)) {
            allowed.add("null");
        }

        if (allowed.stream().anyMatch(candidate -> matchesType(value, candidate))) {
            return checkFormat(value, schema, location, violations);
        }
        violations.add("%s: expected %s but found %s (%s)"
                .formatted(location, String.join(" or ", allowed), value.getNodeType().toString().toLowerCase(), abbreviate(value)));
        return false;
    }

    private boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            // An integer schema accepts an integral number. Jackson reads `5` as an int and
            // `5.0` as a double, and only the first satisfies `type: integer`.
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    /**
     * {@code format} is an annotation in JSON Schema 2020-12, so an unrecognised one is
     * correctly ignored rather than reported. The ones checked are the ones this platform
     * publishes and that a client would parse: a {@code date-time} that does not parse and a
     * {@code uuid} that is not one are real contract failures, not cosmetic.
     */
    private boolean checkFormat(JsonNode value, JsonNode schema, String location, List<String> violations) {
        String format = schema.path("format").asText(null);
        if (format == null || !value.isTextual()) {
            return true;
        }
        String text = value.asText();
        boolean valid = switch (format) {
            case "uuid" -> parses(() -> UUID.fromString(text));
            case "date-time" -> parses(() -> java.time.OffsetDateTime.parse(text));
            case "date" -> parses(() -> java.time.LocalDate.parse(text));
            case "uri" -> parses(() -> java.net.URI.create(text));
            default -> true;
        };
        if (!valid) {
            violations.add("%s: `%s` is not a valid %s".formatted(location, text, format));
        }
        return valid;
    }

    private boolean parses(Runnable parse) {
        try {
            parse.run();
            return true;
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return false;
        }
    }

    // ── constraints ─────────────────────────────────────────────────────────────────────

    private void checkEnum(JsonNode value, JsonNode schema, String location, List<String> violations) {
        JsonNode allowed = schema.path("enum");
        if (!allowed.isMissingNode() && !contains(allowed, value)) {
            violations.add("%s: `%s` is not one of the documented values %s"
                    .formatted(location, abbreviate(value), allowed));
        }
        JsonNode constant = schema.path("const");
        if (!constant.isMissingNode() && !constant.equals(value)) {
            violations.add("%s: expected the constant %s but found %s"
                    .formatted(location, constant, abbreviate(value)));
        }
    }

    private void checkStringConstraints(JsonNode value, JsonNode schema, String location,
                                        List<String> violations) {
        if (!value.isTextual()) {
            return;
        }
        String text = value.asText();
        if (schema.has("maxLength") && text.length() > schema.path("maxLength").asInt()) {
            violations.add("%s: %d characters exceeds the documented maximum of %d"
                    .formatted(location, text.length(), schema.path("maxLength").asInt()));
        }
        if (schema.has("minLength") && text.length() < schema.path("minLength").asInt()) {
            violations.add("%s: %d characters is below the documented minimum of %d"
                    .formatted(location, text.length(), schema.path("minLength").asInt()));
        }
        if (schema.has("pattern")) {
            String pattern = schema.path("pattern").asText();
            try {
                if (!Pattern.compile(pattern).matcher(text).find()) {
                    violations.add("%s: `%s` does not match the documented pattern %s"
                            .formatted(location, text, pattern));
                }
            } catch (PatternSyntaxException e) {
                violations.add("%s: the documented pattern %s is not a valid regular expression"
                        .formatted(location, pattern));
            }
        }
    }

    private void checkNumberConstraints(JsonNode value, JsonNode schema, String location,
                                        List<String> violations) {
        if (!value.isNumber()) {
            return;
        }
        double number = value.asDouble();
        if (schema.has("maximum") && number > schema.path("maximum").asDouble()) {
            violations.add("%s: %s exceeds the documented maximum of %s"
                    .formatted(location, value, schema.path("maximum")));
        }
        if (schema.has("minimum") && number < schema.path("minimum").asDouble()) {
            violations.add("%s: %s is below the documented minimum of %s"
                    .formatted(location, value, schema.path("minimum")));
        }
        if (schema.has("exclusiveMaximum") && number >= schema.path("exclusiveMaximum").asDouble()) {
            violations.add("%s: %s is not below the documented exclusive maximum of %s"
                    .formatted(location, value, schema.path("exclusiveMaximum")));
        }
        if (schema.has("exclusiveMinimum") && number <= schema.path("exclusiveMinimum").asDouble()) {
            violations.add("%s: %s is not above the documented exclusive minimum of %s"
                    .formatted(location, value, schema.path("exclusiveMinimum")));
        }
    }

    private void checkArray(JsonNode value, JsonNode schema, String location, List<String> violations) {
        if (!value.isArray()) {
            return;
        }
        if (schema.has("maxItems") && value.size() > schema.path("maxItems").asInt()) {
            violations.add("%s: %d items exceeds the documented maximum of %d"
                    .formatted(location, value.size(), schema.path("maxItems").asInt()));
        }
        if (schema.has("minItems") && value.size() < schema.path("minItems").asInt()) {
            violations.add("%s: %d items is below the documented minimum of %d"
                    .formatted(location, value.size(), schema.path("minItems").asInt()));
        }
        if (schema.path("uniqueItems").asBoolean(false)) {
            List<JsonNode> seen = new ArrayList<>();
            value.forEach(item -> {
                if (seen.contains(item)) {
                    violations.add("%s: the array is documented as unique but repeats %s"
                            .formatted(location, abbreviate(item)));
                }
                seen.add(item);
            });
        }
        JsonNode items = schema.path("items");
        if (!items.isMissingNode()) {
            for (int i = 0; i < value.size(); i++) {
                validate(value.get(i), items, "%s[%d]".formatted(location, i), violations);
            }
        }
    }

    /**
     * The closed-object rule lives here. Read the three branches in order: a documented
     * field is validated, an undocumented one is measured against
     * {@code additionalProperties}, and — when the schema names properties but says nothing
     * about additional ones — reported.
     */
    private void checkObject(JsonNode value, JsonNode schema, String location, List<String> violations) {
        if (!value.isObject()) {
            return;
        }

        for (JsonNode name : schema.path("required")) {
            if (!value.has(name.asText())) {
                violations.add("%s: the documented required field `%s` is absent"
                        .formatted(location, name.asText()));
            }
        }

        JsonNode properties = schema.path("properties");
        JsonNode additional = schema.path("additionalProperties");
        // A schema with neither `properties` nor `additionalProperties` is an untyped
        // object and accepts anything — audit-service's opaque event payload (D44) is
        // exactly this, and it must stay that way.
        boolean untyped = properties.isMissingNode() && additional.isMissingNode();
        if (untyped) {
            return;
        }

        value.properties().forEach(entry -> {
            String name = entry.getKey();
            String fieldLocation = location.isEmpty() ? name : location + "." + name;
            JsonNode property = properties.path(name);

            if (!property.isMissingNode()) {
                validate(entry.getValue(), property, fieldLocation, violations);
            } else if (additional.isObject()) {
                validate(entry.getValue(), additional, fieldLocation, violations);
            } else if (additional.isBoolean() && !additional.asBoolean()) {
                violations.add("%s: the document forbids additional fields, but the response carries this one"
                        .formatted(fieldLocation));
            } else if (additional.isMissingNode()) {
                violations.add("%s: the response carries a field the document does not describe"
                        .formatted(fieldLocation));
            }
        });
    }

    /**
     * Composition is validated but deliberately not simplified: {@code oneOf} reports the
     * count rather than the sub-schema failures, because a value that matched none of five
     * alternatives produces five irrelevant error lists and one useful sentence.
     */
    private void checkComposition(JsonNode value, JsonNode schema, String location,
                                  List<String> violations) {
        for (JsonNode branch : schema.path("allOf")) {
            validate(value, branch, location, violations);
        }
        JsonNode anyOf = schema.path("anyOf");
        if (anyOf.isArray() && !matchesAny(value, anyOf, location)) {
            violations.add("%s: the value matches none of the %d documented alternatives"
                    .formatted(location, anyOf.size()));
        }
        JsonNode oneOf = schema.path("oneOf");
        if (oneOf.isArray()) {
            long matches = countMatches(value, oneOf, location);
            if (matches != 1) {
                violations.add("%s: the value matches %d of the %d documented alternatives, not exactly one"
                        .formatted(location, matches, oneOf.size()));
            }
        }
    }

    private boolean matchesAny(JsonNode value, JsonNode branches, String location) {
        for (JsonNode branch : branches) {
            if (validate(value, branch, location).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private long countMatches(JsonNode value, JsonNode branches, String location) {
        long matches = 0;
        for (JsonNode branch : branches) {
            if (validate(value, branch, location).isEmpty()) {
                matches++;
            }
        }
        return matches;
    }

    private static boolean contains(JsonNode array, JsonNode value) {
        for (JsonNode candidate : array) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** Violations are read in a test failure message; a 4 KB payload inlined there helps nobody. */
    private static String abbreviate(JsonNode value) {
        String text = value.toString();
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }
}
