package com.paymentflow.agentic.tool;

import com.paymentflow.agentic.error.AgenticErrorCode;
import com.paymentflow.agentic.error.AgenticException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Strict, typed reading of whatever the model actually sent.
 *
 * <p>This class treats its input as hostile, because it is: a tool call is free-form JSON
 * produced by a language model, and the only thing known about it before this point is that
 * it parsed. Every accessor here either returns a value of the requested type within the
 * requested bounds, or throws {@link AgenticErrorCode#TOOL_ARGUMENTS_INVALID}. Nothing is
 * coerced, defaulted silently, or best-guessed.
 *
 * <h2>Failure messages never echo the value</h2>
 *
 * <p>A rejection says which argument was wrong and what was expected of it — never what was
 * supplied. Two reasons, and the second is the load-bearing one. The message goes into a log,
 * and it goes back to the model as a tool result; echoing the offending value would put
 * unbounded model-authored text into both. A model that had put a credential in the wrong
 * field would then have that credential written to the log by the very code that rejected it.
 *
 * <h2>Unknown arguments are rejected, not ignored</h2>
 *
 * <p>{@link #requireOnly} refuses a call carrying any field the schema does not declare.
 * Dropping unknown fields silently would let a model believe it had supplied an amount, an
 * override or a flag that was never received — and the model would then tell the buyer so.
 */
public final class ToolArguments {

    private final String toolName;
    private final Map<String, Object> raw;

    private ToolArguments(String toolName, Map<String, Object> raw) {
        this.toolName = toolName;
        this.raw = raw == null ? Map.of() : new LinkedHashMap<>(raw);
    }

    public static ToolArguments of(String toolName, Map<String, Object> raw) {
        return new ToolArguments(toolName, raw);
    }

    /**
     * Rejects any argument the schema does not declare.
     *
     * <p>Called first by every tool's {@code validate}, before any value is read, so a call
     * with a stray field fails on the stray field rather than half-succeeding on the valid
     * ones.
     */
    public ToolArguments requireOnly(ToolSchema schema) {
        Set<String> declared = schema.propertyNames();
        Set<String> unexpected = new TreeSet<>(raw.keySet());
        unexpected.removeAll(declared);
        if (!unexpected.isEmpty()) {
            throw invalid("does not accept the argument(s) %s. Accepted arguments are %s."
                    .formatted(unexpected, new TreeSet<>(declared)));
        }
        return this;
    }

    // ── Scalars ─────────────────────────────────────────────────────────────────────────

    public UUID requireUuid(String key) {
        String value = requireString(key, 64);
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw invalidArgument(key, "must be a UUID");
        }
    }

    public UUID optionalUuid(String key) {
        return has(key) ? requireUuid(key) : null;
    }

    public String requireString(String key, int maxLength) {
        Object value = raw.get(key);
        if (value == null) {
            throw invalidArgument(key, "is required");
        }
        if (!(value instanceof String text)) {
            throw invalidArgument(key, "must be a string");
        }
        if (text.isBlank()) {
            throw invalidArgument(key, "must not be blank");
        }
        if (text.length() > maxLength) {
            throw invalidArgument(key, "must be at most %d characters".formatted(maxLength));
        }
        return text;
    }

    /** Returns {@code null} when absent. An argument that is present must still be valid. */
    public String optionalString(String key, int maxLength) {
        return has(key) ? requireString(key, maxLength) : null;
    }

    public long requireLong(String key, long minimum, long maximum) {
        Object value = raw.get(key);
        if (value == null) {
            throw invalidArgument(key, "is required");
        }
        long number = toLong(key, value);
        if (number < minimum || number > maximum) {
            throw invalidArgument(key, "must be between %d and %d".formatted(minimum, maximum));
        }
        return number;
    }

    public Long optionalLong(String key, long minimum, long maximum) {
        return has(key) ? requireLong(key, minimum, maximum) : null;
    }

    public int requireInt(String key, int minimum, int maximum) {
        return Math.toIntExact(requireLong(key, minimum, maximum));
    }

    // ── Objects and arrays ──────────────────────────────────────────────────────────────

    /**
     * Reads an array of objects as a list of readers, so each element is validated by the
     * same strict rules as a top-level argument rather than by a second, looser code path.
     */
    public List<ToolArguments> requireObjectList(String key, int minItems, int maxItems) {
        Object value = raw.get(key);
        if (value == null) {
            throw invalidArgument(key, "is required");
        }
        if (!(value instanceof List<?> list)) {
            throw invalidArgument(key, "must be an array");
        }
        if (list.size() < minItems || list.size() > maxItems) {
            throw invalidArgument(key, "must contain between %d and %d items".formatted(minItems, maxItems));
        }

        List<ToolArguments> elements = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            Object element = list.get(index);
            if (!(element instanceof Map<?, ?> map)) {
                throw invalidArgument("%s[%d]".formatted(key, index), "must be an object");
            }
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String name)) {
                    throw invalidArgument("%s[%d]".formatted(key, index), "must have string keys");
                }
                typed.put(name, entry.getValue());
            }
            elements.add(new ToolArguments("%s.%s[%d]".formatted(toolName, key, index), typed));
        }
        return elements;
    }

    public boolean has(String key) {
        return raw.get(key) != null;
    }

    /**
     * The arguments as read, for the action log's redacted summary.
     *
     * <p>Returns a copy. The caller is {@code Redactor}, and handing it the live map would let
     * a redaction pass mutate what a later step reads.
     */
    public Map<String, Object> asMap() {
        return new LinkedHashMap<>(raw);
    }

    // ── Coercion and failure ────────────────────────────────────────────────────────────

    /**
     * Accepts an integral JSON number, and a string of digits.
     *
     * <p>The string case is a concession to how models actually behave — {@code "2"} for a
     * quantity is common and unambiguous. A fractional value is <em>not</em> accepted: money
     * and quantities are integers in this platform without exception, and silently truncating
     * {@code 2.7} is how an off-by-one becomes a charge.
     */
    private long toLong(String key, Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Number number) {
            double asDouble = number.doubleValue();
            if (asDouble != Math.floor(asDouble) || Double.isInfinite(asDouble)) {
                throw invalidArgument(key, "must be a whole number");
            }
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                throw invalidArgument(key, "must be a whole number");
            }
        }
        throw invalidArgument(key, "must be a whole number");
    }

    private AgenticException invalidArgument(String key, String expectation) {
        return invalid("argument '%s' %s.".formatted(key, expectation));
    }

    private AgenticException invalid(String detail) {
        return new AgenticException(AgenticErrorCode.TOOL_ARGUMENTS_INVALID,
                "Tool '%s' %s".formatted(toolName, detail));
    }
}
