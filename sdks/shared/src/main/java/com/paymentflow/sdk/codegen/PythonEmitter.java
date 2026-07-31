package com.paymentflow.sdk.codegen;

import com.paymentflow.sdk.codegen.SdkSpec.SdkEnum;
import com.paymentflow.sdk.codegen.SdkSpec.SdkField;
import com.paymentflow.sdk.codegen.SdkSpec.SdkModel;
import com.paymentflow.sdk.codegen.SdkSpec.SdkOperation;
import com.paymentflow.sdk.codegen.SdkSpec.SdkParameter;
import com.paymentflow.sdk.codegen.SdkSpec.SdkType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Emits the Python SDK's generated modules (M22.1).
 *
 * <p>The same three files as {@link TypeScriptEmitter}, describing the same contract, so that
 * a difference between the SDKs can only ever be idiom.
 *
 * <p><b>Models are {@code TypedDict}s, not dataclasses.</b> A payments SDK has to tolerate a
 * response field it has never heard of — §9 makes "additive changes are never breaking" a
 * promise, and a dataclass constructor rejecting an unknown keyword would break every
 * integrator the first time the platform added a field. A {@code TypedDict} is a plain
 * {@code dict} at runtime, so an unknown key costs nothing and is still there for a caller who
 * wants it, while a type checker still sees every documented field. {@code total=False}
 * because this document marks almost nothing required, and saying otherwise would make a type
 * checker demand keys the API does not always send.
 *
 * <p><b>Targets Python 3.9.</b> {@code from __future__ import annotations} makes the modern
 * union spelling legal in annotations on every supported version, so the emitted code needs no
 * {@code typing_extensions} and the package keeps its one runtime dependency.
 */
final class PythonEmitter {

    /** Where the generated modules land, relative to the repository root. */
    static final String DIRECTORY = "sdks/python/src/paymentflow/_generated";

    private static final String HEADER = """
            # Generated from docs/openapi.yaml by :sdks:shared. Do not edit.
            #
            # `./gradlew :sdks:shared:generateSdkSources` regenerates this module;
            # `./gradlew :sdks:shared:verifySdkSources` fails the build when it is stale,
            # which is why hand-editing it is not merely discouraged but pointless.

            from __future__ import annotations

            """;

    private PythonEmitter() {
    }

    static Map<String, String> emit(SdkSpec spec) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(DIRECTORY + "/__init__.py", packageInit());
        files.put(DIRECTORY + "/contract.py", contract(spec));
        files.put(DIRECTORY + "/models.py", models(spec));
        files.put(DIRECTORY + "/operations.py", operations(spec));
        return files;
    }

    private static String packageInit() {
        return """
                # Generated from docs/openapi.yaml by :sdks:shared. Do not edit.
                #
                # Deliberately re-exports nothing. This package is an implementation detail of
                # the `paymentflow` package: what an SDK user may rely on is decided by the
                # hand-written layer above, and a generated `__init__` that re-exported
                # everything would make every regeneration a change to the public API.

                from __future__ import annotations
                """;
    }

    // ── contract.py ─────────────────────────────────────────────────────────────────────

    private static String contract(SdkSpec spec) {
        return HEADER + """
                from typing import Final

                #: The dated API revision this SDK was generated against and sends by default.
                API_VERSION: Final[str] = %s

                #: The published host. Overridable through the client's ``base_url`` option.
                DEFAULT_BASE_URL: Final[str] = %s

                #: The title of the contract this SDK implements.
                API_TITLE: Final[str] = %s
                """.formatted(quote(spec.apiVersion()), quote(spec.serverUrl()), quote(spec.title()));
    }

    // ── models.py ───────────────────────────────────────────────────────────────────────

    private static String models(SdkSpec spec) {
        StringBuilder out = new StringBuilder(HEADER);
        out.append("from typing import Any, Dict, Final, List, Optional, Tuple, TypedDict\n\n");

        for (SdkEnum declared : spec.enums()) {
            // `str`, not `Literal[...]`. §9 requires a client to tolerate an enum value it has
            // never heard of, and a `Literal` annotation makes a type checker reject exactly
            // that — it would turn the platform's safest kind of change into a type error in
            // every integrator's code at once. The documented values are exported beside it as
            // a tuple, which is what the parity fixtures compare and what a caller matches
            // against when it wants to know whether it recognises a value.
            out.append(alias(declared.name())).append(" = str\n");
            out.append(docstring(enumDoc(declared), ""));
            out.append(constantName(declared.name())).append(": Final[Tuple[str, ...]] = (\n");
            for (String value : declared.values()) {
                out.append("    ").append(quote(value)).append(",\n");
            }
            out.append(")\n\n");
        }

        for (SdkModel model : spec.models()) {
            out.append(model.fields().stream().allMatch(field -> isIdentifier(field.name()))
                    ? classModel(spec, model)
                    : functionalModel(spec, model));
        }
        return out.toString().stripTrailing() + "\n";
    }

    /**
     * The readable form: a class body, one annotation per field, each with its own docstring.
     *
     * <p>{@code total=False} because this document marks almost nothing required, and a
     * TypedDict that claimed otherwise would make a type checker demand keys the API does not
     * always send.
     */
    private static String classModel(SdkSpec spec, SdkModel model) {
        StringBuilder out = new StringBuilder();
        out.append("class ").append(model.name()).append("(TypedDict, total=False):\n");
        out.append(docstring(model.description() != null ? model.description() : model.name(), "    "));
        if (model.fields().isEmpty()) {
            return out.append("    pass\n\n").toString();
        }
        for (SdkField field : model.fields()) {
            out.append("    ").append(field.name()).append(": ")
                    .append(type(spec, model.name(), field.name(), field.type())).append('\n');
            if (field.description() != null) {
                out.append(docstring(field.description(), "    "));
            }
        }
        return out.append('\n').toString();
    }

    /**
     * The form a model needs when one of its fields is not a Python identifier.
     *
     * <p>The analytics and usage summaries both publish a window as {@code from}/{@code to},
     * and {@code from} is a Python keyword — a class body declaring it is a syntax error, so
     * the module would not import at all. Found by running the suite rather than by reading
     * the emitter, which is the only way this class of bug is ever found.
     *
     * <p>Renaming the field was the alternative and is worse: the wire name is what a response
     * actually contains, and a model whose keys do not match the JSON is a model no
     * deserializer can use. TypedDict's functional form exists for exactly this, costs the
     * per-field docstrings — which move into the class docstring — and keeps
     * {@code __annotations__} intact, so the parity test still sees every field.
     */
    private static String functionalModel(SdkSpec spec, SdkModel model) {
        StringBuilder documentation = new StringBuilder(
                model.description() != null ? model.description() : model.name());
        StringBuilder out = new StringBuilder();
        out.append(model.name()).append(" = TypedDict(\n")
                .append("    ").append(quote(model.name())).append(",\n")
                .append("    {\n");
        for (SdkField field : model.fields()) {
            out.append("        ").append(quote(field.name())).append(": ")
                    .append(type(spec, model.name(), field.name(), field.type())).append(",\n");
            if (field.description() != null) {
                documentation.append("\n\n``").append(field.name()).append("``: ").append(field.description());
            }
        }
        out.append("    },\n    total=False,\n)\n");
        out.append(docstring(documentation.toString(), ""));
        return out.append('\n').toString();
    }

    /**
     * Python's reserved words, which a class body cannot use as a field name.
     *
     * <p>The list is 3.9's and is closed — Python has added only <em>soft</em> keywords since
     * ({@code match}, {@code case}, {@code type}), and those are ordinary identifiers by
     * design. Spelled out rather than derived, because the derivation would be "whatever the
     * JDK's Python is", and there is none.
     */
    private static final java.util.Set<String> RESERVED = java.util.Set.of(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
            "continue", "def", "del", "elif", "else", "except", "finally", "for", "from",
            "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass",
            "raise", "return", "try", "while", "with", "yield");

    private static boolean isIdentifier(String name) {
        return name.matches("[A-Za-z_][A-Za-z0-9_]*") && !RESERVED.contains(name);
    }

    // ── operations.py ───────────────────────────────────────────────────────────────────

    private static String operations(SdkSpec spec) {
        StringBuilder out = new StringBuilder(HEADER);
        out.append("""
                from typing import Dict, Final, Tuple, TypedDict


                class OperationDescriptor(TypedDict):
                    \"""One published operation, addressed by the operation id M21.7 made unique.\"""

                    id: str
                    method: str
                    path: str
                    tag: str
                    summary: str
                    success_status: str
                    query_parameters: Tuple[str, ...]
                    has_request_body: bool


                OPERATIONS: Final[Dict[str, OperationDescriptor]] = {
                """);
        for (SdkOperation operation : spec.operations()) {
            out.append("    ").append(quote(operation.id())).append(": {\n")
                    .append("        \"id\": ").append(quote(operation.id())).append(",\n")
                    .append("        \"method\": ").append(quote(operation.method())).append(",\n")
                    .append("        \"path\": ").append(quote(operation.path())).append(",\n")
                    .append("        \"tag\": ").append(quote(operation.tag())).append(",\n")
                    .append("        \"summary\": ").append(quote(operation.summary())).append(",\n")
                    .append("        \"success_status\": ")
                    .append(quote(operation.success() == null ? "" : operation.success().status())).append(",\n")
                    .append("        \"query_parameters\": (");
            for (SdkParameter parameter : operation.parameters()) {
                if ("query".equals(parameter.in())) {
                    // A trailing comma on every element, so a one-element tuple is a tuple
                    // rather than a parenthesised string — Python's oldest sharp edge.
                    out.append(quote(parameter.name())).append(", ");
                }
            }
            out.append("),\n")
                    .append("        \"has_request_body\": ")
                    .append(operation.requestModel() != null ? "True" : "False").append(",\n")
                    .append("    },\n");
        }
        out.append("}\n");
        return out.toString();
    }

    // ── Types ───────────────────────────────────────────────────────────────────────────

    private static String type(SdkSpec spec, String model, String field, SdkType type) {
        String base = switch (type.kind()) {
            case STRING, DATE, DATE_TIME, UUID -> enumFor(spec, model, field);
            case INTEGER -> "int";
            case NUMBER -> "float";
            case BOOLEAN -> "bool";
            // Quoted: a model may reference one defined later in the file, and this document
            // has several. A forward reference is legal in an annotation, an undefined name is
            // not, and which of the two it is depends on emission order — so quote all of them
            // rather than depend on a sort staying favourable.
            case REFERENCE -> "\"" + type.reference() + "\"";
            case ARRAY -> "List[" + type(spec, model, field, type.item()) + "]";
            case MAP -> "Dict[str, " + type(spec, model, field, type.item()) + "]";
            // The event payload (D44), whose shape is deliberately open.
            case OBJECT -> "Dict[str, Any]";
            case UNKNOWN -> "Any";
        };
        // `Optional[T]` rather than omitting the key. An explicitly-null field and an absent
        // one mean different things here (D143), and `total=False` already covers absence.
        return type.nullable() ? "Optional[" + base + "]" : base;
    }

    private static String enumFor(SdkSpec spec, String model, String field) {
        for (SdkEnum declared : spec.enums()) {
            if (declared.owner().equals(model) && declared.field().equals(field)) {
                return alias(declared.name());
            }
        }
        return "str";
    }

    // ── Rendering ───────────────────────────────────────────────────────────────────────

    private static String alias(String name) {
        return name;
    }

    private static String constantName(String typeName) {
        return typeName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT) + "_VALUES";
    }

    private static String enumDoc(SdkEnum declared) {
        String description = declared.description() != null ? declared.description() : "";
        return (description.isBlank() ? declared.owner() + "." + declared.field() : description)
                + "\n\nNew values may be added without a new API revision, so this alias stays "
                + "``str``: treat an unrecognised value as one you do not handle rather than as an error.";
    }

    /**
     * A docstring. Triple quotes are escaped and a trailing backslash neutralised, because
     * either would end the literal early and produce a module that does not import — the kind
     * of breakage that is invisible in review and total at runtime.
     */
    private static String docstring(String text, String indent) {
        String body = text.replace("\\", "\\\\").replace("\"\"\"", "\\\"\\\"\\\"");
        StringBuilder out = new StringBuilder(indent).append("\"\"\"");
        String[] lines = body.split("\n", -1);
        if (lines.length == 1) {
            return out.append(lines[0]).append("\"\"\"\n").toString();
        }
        out.append(lines[0]).append('\n');
        for (int i = 1; i < lines.length; i++) {
            out.append(lines[i].isEmpty() ? "" : indent).append(lines[i]).append('\n');
        }
        return out.append(indent).append("\"\"\"\n").toString();
    }

    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder out = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(character);
            }
        }
        return out.append('"').toString();
    }
}
