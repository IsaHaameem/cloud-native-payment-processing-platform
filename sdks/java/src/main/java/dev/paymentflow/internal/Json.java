package dev.paymentflow.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader, writer, and {@code Map}&#8594;{@code record} mapper — the one thing the
 * JDK has no answer for and the reason this SDK is not quite dependency-free in spirit.
 *
 * <p>Deliberately not a general JSON library. The PaymentFlow contract's data is strings,
 * integers, booleans, string maps, and nested objects and arrays of those; there are no
 * comments, no NaN, no bare top-level scalars in a response body this SDK reads. So this parser
 * is a plain recursive descent over exactly that grammar, with its own test
 * ({@code JsonTest}) against the shapes the API actually returns. Bringing in Jackson to do this
 * would add a transitive tree to a payments SDK for the sake of ~200 lines.
 *
 * <p><b>Numbers.</b> A literal with no fraction or exponent parses to {@link Long}, otherwise to
 * {@link Double}. The record mapper coerces between the two, so a field typed {@code Long} still
 * reads a response that sent {@code 1.0} and a {@code Double} field still reads {@code 1}.
 *
 * <p><b>Unknown fields ride along.</b> {@link #toRecord} takes the keys the record declares and
 * ignores the rest — §9's forward-compatibility promise is that a field added to a response is
 * never breaking, and a mapper that threw on an unrecognised key would break it. A key the
 * record declares but the response omits maps to {@code null}.
 */
public final class Json {

    private Json() {}

    // ── Parsing ─────────────────────────────────────────────────────────────────────────────

    /** Parses one JSON document. Returns {@code Map}, {@code List}, {@code String}, {@code Long},
     *  {@code Double}, {@code Boolean}, or {@code null}. */
    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonException("trailing characters after JSON value at position " + parser.pos);
        }
        return value;
    }

    /** Parses a document known to be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBoolean();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                out.put(key, readValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return out;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or '}' in object at position " + (pos - 1));
                }
            }
        }

        List<Object> readArray() {
            expect('[');
            List<Object> out = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                out.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return out;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or ']' in array at position " + (pos - 1));
                }
            }
        }

        String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            out.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new JsonException("invalid escape \\" + e);
                    }
                } else {
                    out.append(c);
                }
            }
        }

        Object readNumber() {
            int start = pos;
            boolean floating = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '-' || c == '+' || (c >= '0' && c <= '9')) {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E') {
                    floating = true;
                    pos++;
                } else {
                    break;
                }
            }
            String literal = s.substring(start, pos);
            if (literal.isEmpty()) {
                throw new JsonException("expected a value at position " + start);
            }
            try {
                return floating ? (Object) Double.parseDouble(literal) : (Object) Long.parseLong(literal);
            } catch (NumberFormatException e) {
                // A very large integer literal is still a number; fall back to double rather than fail.
                return Double.parseDouble(literal);
            }
        }

        Boolean readBoolean() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new JsonException("invalid literal at position " + pos);
        }

        Object readNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new JsonException("invalid literal at position " + pos);
        }

        char peek() {
            if (atEnd()) {
                throw new JsonException("unexpected end of input");
            }
            return s.charAt(pos);
        }

        char next() {
            return s.charAt(pos++);
        }

        void expect(char c) {
            if (atEnd() || s.charAt(pos) != c) {
                throw new JsonException("expected '" + c + "' at position " + pos);
            }
            pos++;
        }
    }

    // ── Writing ─────────────────────────────────────────────────────────────────────────────

    /** Serializes a value built from {@code Map}, {@code List}, {@code String}, {@code Number},
     *  {@code Boolean} and {@code null}. Compact, no spaces. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            writeString(out, string);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                out.append(Long.toString((long) d));
            } else {
                out.append(d);
            }
        } else if (value instanceof Number number) {
            out.append(number);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                writeValue(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object element : iterable) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeValue(out, element);
            }
            out.append(']');
        } else {
            throw new JsonException("cannot serialize " + value.getClass().getName());
        }
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ── Map → record ────────────────────────────────────────────────────────────────────────

    /** Maps a parsed JSON object to a record, ignoring keys the record does not declare. */
    @SuppressWarnings("unchecked")
    public static <T> T toRecord(Object node, Class<T> type) {
        if (node == null) {
            return null;
        }
        if (!(node instanceof Map)) {
            throw new JsonException("expected a JSON object for " + type.getSimpleName() + ", got "
                    + node.getClass().getSimpleName());
        }
        Map<String, Object> map = (Map<String, Object>) node;
        RecordComponent[] components = type.getRecordComponents();
        if (components == null) {
            throw new JsonException(type.getName() + " is not a record");
        }
        Object[] args = new Object[components.length];
        Class<?>[] paramTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            paramTypes[i] = components[i].getType();
            args[i] = convert(map.get(components[i].getName()), components[i].getGenericType());
        }
        try {
            var constructor = type.getDeclaredConstructor(paramTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException e) {
            throw new JsonException("cannot construct " + type.getName(), e);
        } catch (InvocationTargetException e) {
            throw new JsonException("constructing " + type.getName() + " failed", e.getCause());
        }
    }

    @SuppressWarnings("unchecked")
    private static Object convert(Object raw, Type target) {
        if (raw == null) {
            return null;
        }
        if (target instanceof Class<?> cls) {
            if (cls == Object.class) {
                return raw;
            }
            if (cls == String.class) {
                return raw instanceof String ? raw : String.valueOf(raw);
            }
            if (cls == Long.class || cls == long.class) {
                return ((Number) raw).longValue();
            }
            if (cls == Integer.class || cls == int.class) {
                return ((Number) raw).intValue();
            }
            if (cls == Double.class || cls == double.class) {
                return ((Number) raw).doubleValue();
            }
            if (cls == Boolean.class || cls == boolean.class) {
                return raw;
            }
            if (cls.isRecord()) {
                return toRecord(raw, cls);
            }
            return raw;
        }
        if (target instanceof ParameterizedType pt) {
            Class<?> rawType = (Class<?>) pt.getRawType();
            Type[] typeArgs = pt.getActualTypeArguments();
            if (List.class.isAssignableFrom(rawType)) {
                if (!(raw instanceof List)) {
                    throw new JsonException("expected a JSON array, got " + raw.getClass().getSimpleName());
                }
                List<Object> out = new ArrayList<>();
                for (Object element : (List<Object>) raw) {
                    out.add(convert(element, typeArgs[0]));
                }
                // Not List.copyOf: it rejects a null element, and while this contract never sends
                // one in an array, losing it would be the wrong way to find that out.
                return java.util.Collections.unmodifiableList(out);
            }
            if (Map.class.isAssignableFrom(rawType)) {
                if (!(raw instanceof Map)) {
                    throw new JsonException("expected a JSON object, got " + raw.getClass().getSimpleName());
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : ((Map<String, Object>) raw).entrySet()) {
                    out.put(entry.getKey(), convert(entry.getValue(), typeArgs[1]));
                }
                return java.util.Collections.unmodifiableMap(out);
            }
        }
        return raw;
    }
}
