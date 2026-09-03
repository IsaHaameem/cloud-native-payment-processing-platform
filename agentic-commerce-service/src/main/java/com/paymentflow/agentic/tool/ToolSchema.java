package com.paymentflow.agentic.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A tool's declared input shape — the contract shown to the model, and the contract the
 * arguments are checked against.
 *
 * <p>One declaration serves both, on purpose. A schema that is published to the model and a
 * validator written separately by hand will drift, and the drift is silent: the model keeps
 * sending what it was told, the validator keeps checking something else, and the gap between
 * them is where a malformed money argument gets through. {@link ToolArguments} reads the
 * declaration directly.
 *
 * <p><b>{@code additionalProperties} is always {@code false}.</b> The model is told, in the
 * schema itself, that unlisted fields are not accepted — and {@link ToolArguments#requireOnly}
 * enforces it rather than trusting the model to have read it. An unexpected field is a
 * rejected call, not an ignored one: quietly dropping it would let a model believe it had
 * supplied an amount, an override or a flag that was never received.
 */
public record ToolSchema(List<Property> properties) {

    public ToolSchema {
        properties = List.copyOf(properties);
    }

    /** The types a tool argument may have. Deliberately few; a tool needing more is a tool doing too much. */
    public enum PropertyType {
        STRING("string"),
        INTEGER("integer"),
        ARRAY("array");

        private final String jsonName;

        PropertyType(String jsonName) {
            this.jsonName = jsonName;
        }

        String jsonName() {
            return jsonName;
        }
    }

    /**
     * One declared argument.
     *
     * @param itemSchema for {@link PropertyType#ARRAY}, the shape of each element. Arrays of
     *                   scalars are not supported and are not needed — every array this
     *                   service accepts is a list of line items.
     */
    public record Property(
            String name,
            PropertyType type,
            String description,
            boolean required,
            Long minimum,
            Long maximum,
            Integer maxLength,
            Integer maxItems,
            ToolSchema itemSchema) {

        public Property {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(description, "description");
        }
    }

    public Set<String> propertyNames() {
        return properties.stream().map(Property::name).collect(Collectors.toUnmodifiableSet());
    }

    public List<String> requiredNames() {
        return properties.stream().filter(Property::required).map(Property::name).toList();
    }

    /**
     * The JSON Schema object handed to the model as a tool's {@code input_schema}.
     *
     * <p>A {@code LinkedHashMap} throughout so the rendering is byte-stable across runs. The
     * schema is part of the prompt; a prompt whose key order changes between JVM runs
     * defeats provider-side prompt caching and makes two otherwise-identical requests
     * incomparable in a log.
     */
    public Map<String, Object> toJsonSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> props = new LinkedHashMap<>();
        for (Property property : properties) {
            props.put(property.name(), describe(property));
        }
        schema.put("properties", props);

        List<String> required = requiredNames();
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> describe(Property property) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", property.type().jsonName());
        node.put("description", property.description());
        if (property.minimum() != null) {
            node.put("minimum", property.minimum());
        }
        if (property.maximum() != null) {
            node.put("maximum", property.maximum());
        }
        if (property.maxLength() != null) {
            node.put("maxLength", property.maxLength());
        }
        if (property.maxItems() != null) {
            node.put("maxItems", property.maxItems());
        }
        if (property.itemSchema() != null) {
            node.put("items", property.itemSchema().toJsonSchema());
        }
        return node;
    }

    // ── Builder ─────────────────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    /** Declares properties in the order they are added, which is the order the model sees them. */
    public static final class Builder {

        private final List<Property> properties = new ArrayList<>();

        public Builder string(String name, String description, boolean required, int maxLength) {
            properties.add(new Property(name, PropertyType.STRING, description, required, null, null,
                    maxLength, null, null));
            return this;
        }

        public Builder integer(String name, String description, boolean required, long minimum, long maximum) {
            properties.add(new Property(name, PropertyType.INTEGER, description, required, minimum, maximum,
                    null, null, null));
            return this;
        }

        public Builder arrayOfObjects(String name, String description, boolean required, int maxItems,
                                      ToolSchema itemSchema) {
            properties.add(new Property(name, PropertyType.ARRAY, description, required, null, null, null,
                    maxItems, itemSchema));
            return this;
        }

        public ToolSchema build() {
            return new ToolSchema(properties);
        }
    }
}
