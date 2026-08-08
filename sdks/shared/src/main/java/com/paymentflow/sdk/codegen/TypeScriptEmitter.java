package com.paymentflow.sdk.codegen;

import com.paymentflow.sdk.codegen.SdkSpec.SdkEnum;
import com.paymentflow.sdk.codegen.SdkSpec.SdkField;
import com.paymentflow.sdk.codegen.SdkSpec.SdkModel;
import com.paymentflow.sdk.codegen.SdkSpec.SdkOperation;
import com.paymentflow.sdk.codegen.SdkSpec.SdkType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Emits the Node SDK's generated TypeScript (M22.1).
 *
 * <p>Three files, and the split is deliberate: a caller reading a stack trace or a diff
 * should be able to tell "the contract's data shapes changed" from "an endpoint moved".
 *
 * <p><b>Everything here is types and constants — no behaviour.</b> The blueprint's rule is
 * that generated code is an internal implementation detail and never part of the public SDK
 * API; the way to make that hold rather than to intend it is for the generated code to have
 * no API worth exposing. The hand-written layer imports these, uses them, and re-exports only
 * what it chose to.
 */
final class TypeScriptEmitter {

    /** Where the Node SDK's generated files land, relative to the repository root. */
    static final String DIRECTORY = "sdks/node/src/generated";

    /**
     * And where the developer portal's copy lands (M23.1, D189).
     *
     * <p>The same bytes, written twice, deliberately. The portal needs the contract's types
     * and its operation descriptors exactly as much as the SDK does, and the alternatives were
     * worse: a TypeScript codegen step in the portal's own toolchain moves the freshness gate
     * into a build a contributor without Node never runs (D164), and a {@code tsconfig} path
     * alias into {@code sdks/node/src/generated} makes the portal's Docker build context reach
     * across a directory {@code .dockerignore} excludes.
     *
     * <p>This is the same trade already accepted between Node and Python: one reader, several
     * emitters, and no shared artefact that could go stale between them. The duplication is
     * bytes on disk, not a second source of truth — both copies come from one
     * {@link SdkSpec} in one pass, and {@code verifySdkSources} fails the build if either
     * drifts.
     */
    static final String PORTAL_DIRECTORY = "developer-portal/src/generated";

    private static final String HEADER = """
            /*
             * Generated from docs/openapi.yaml by :sdks:shared. Do not edit.
             *
             * `./gradlew :sdks:shared:generateSdkSources` regenerates this file;
             * `./gradlew :sdks:shared:verifySdkSources` fails the build when it is stale,
             * which is why hand-editing it is not merely discouraged but pointless.
             */

            """;

    private TypeScriptEmitter() {
    }

    static Map<String, String> emit(SdkSpec spec) {
        Map<String, String> files = new LinkedHashMap<>();
        for (String directory : List.of(DIRECTORY, PORTAL_DIRECTORY)) {
            files.put(directory + "/contract.ts", contract(spec));
            files.put(directory + "/models.ts", models(spec));
            files.put(directory + "/operations.ts", operations(spec));
        }
        return files;
    }

    // ── contract.ts ─────────────────────────────────────────────────────────────────────

    private static String contract(SdkSpec spec) {
        StringBuilder out = new StringBuilder(HEADER);
        out.append("""
                /** The dated API revision this SDK was generated against and sends by default. */
                export const API_VERSION = %s;

                /** The published host. Overridable through the client's `baseUrl` option. */
                export const DEFAULT_BASE_URL = %s;

                /** The title of the contract this SDK implements. */
                export const API_TITLE = %s;
                """.formatted(quote(spec.apiVersion()), quote(spec.serverUrl()), quote(spec.title())));
        return out.toString();
    }

    // ── models.ts ───────────────────────────────────────────────────────────────────────

    private static String models(SdkSpec spec) {
        StringBuilder out = new StringBuilder(HEADER);

        for (SdkEnum declared : spec.enums()) {
            out.append(doc(enumDoc(declared)));
            // A union of the known literals *plus* `(string & {})`. The union is what gives an
            // editor its completions; the intersection is what keeps the type open, because
            // §9 requires a client to tolerate an enum value it has never heard of and a
            // closed union would make the compiler reject one. TypeScript collapses
            // `"a" | string` to `string` and loses the completions, which is the whole reason
            // for the otherwise strange-looking `(string & {})`.
            out.append("export type ").append(declared.name()).append(" =\n");
            for (String value : declared.values()) {
                out.append("  | ").append(quote(value)).append('\n');
            }
            out.append("  | (string & {});\n\n");

            out.append(doc("The values `" + declared.name() + "` is documented to take today. "
                    + "New ones may be added without a new API revision, so this is a list to "
                    + "recognise against, never one to validate with."));
            out.append("export const ").append(constantName(declared.name())).append(" = [\n");
            for (String value : declared.values()) {
                out.append("  ").append(quote(value)).append(",\n");
            }
            out.append("] as const;\n\n");
        }

        for (SdkModel model : spec.models()) {
            out.append(doc(model.description() != null ? model.description() : model.name()));
            out.append("export interface ").append(model.name()).append(" {\n");
            for (SdkField field : model.fields()) {
                if (field.description() != null) {
                    out.append(indentedDoc(field.description()));
                }
                out.append("  ").append(propertyName(field.name()))
                        .append(field.required() ? "" : "?")
                        .append(": ").append(type(spec, model.name(), field.name(), field.type()))
                        .append(";\n");
            }
            out.append("}\n\n");
        }
        return out.toString().stripTrailing() + "\n";
    }

    /** A comma-separated list of quoted names, for the array literals above. */
    private static String names(List<String> values) {
        return values.stream().map(TypeScriptEmitter::quote).collect(Collectors.joining(", "));
    }

    // ── operations.ts ───────────────────────────────────────────────────────────────────

    private static String operations(SdkSpec spec) {
        StringBuilder out = new StringBuilder(HEADER);
        out.append("""
                /** One published operation, addressed by the operation id M21.7 made unique. */
                export interface OperationDescriptor {
                  readonly id: string;
                  readonly method: string;
                  /** The path template, with `{id}`-style placeholders left in. */
                  readonly path: string;
                  readonly tag: string;
                  readonly summary: string;
                  /** The status the operation returns when nothing went wrong. */
                  readonly successStatus: string;
                  /** Names of the query parameters this operation accepts, in wire spelling. */
                  readonly queryParameters: readonly string[];
                  /**
                   * Header parameters the contract marks `required` — today, `Idempotency-Key` on
                   * the five payment mutations that have always rejected a request without one.
                   *
                   * Read from the document rather than listed in hand-written code: a client that
                   * carried its own copy of "which operations need a key" would keep sending the
                   * old answer after the contract changed, and the failure would be a rejected
                   * request or, worse, a duplicated charge.
                   */
                  readonly requiredHeaders: readonly string[];
                  /** Whether the operation takes a JSON request body. */
                  readonly hasRequestBody: boolean;
                }

                """);
        out.append("export const OPERATIONS = {\n");
        for (SdkOperation operation : spec.operations()) {
            out.append("  ").append(operation.id()).append(": {\n")
                    .append("    id: ").append(quote(operation.id())).append(",\n")
                    .append("    method: ").append(quote(operation.method())).append(",\n")
                    .append("    path: ").append(quote(operation.path())).append(",\n")
                    .append("    tag: ").append(quote(operation.tag())).append(",\n")
                    .append("    summary: ").append(quote(operation.summary())).append(",\n")
                    .append("    successStatus: ")
                    .append(quote(operation.success() == null ? "" : operation.success().status())).append(",\n")
                    .append("    queryParameters: [").append(names(SdkSpec.queryParameters(operation)))
                    .append("] as const,\n")
                    .append("    requiredHeaders: [").append(names(SdkSpec.requiredHeaders(operation)))
                    .append("] as const,\n")
                    .append("    hasRequestBody: ").append(operation.requestModel() != null).append(",\n")
                    .append("  },\n");
        }
        out.append("} as const satisfies Record<string, OperationDescriptor>;\n\n");
        out.append("""
                /** Every operation id, for the parity fixtures and for exhaustive iteration. */
                export type OperationId = keyof typeof OPERATIONS;
                """);
        return out.toString();
    }

    // ── Types ───────────────────────────────────────────────────────────────────────────

    private static String type(SdkSpec spec, String model, String field, SdkType type) {
        String base = switch (type.kind()) {
            case STRING, DATE, DATE_TIME, UUID -> enumFor(spec, model, field);
            case INTEGER, NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case REFERENCE -> type.reference();
            case ARRAY -> type(spec, model, field, type.item()) + "[]";
            case MAP -> "Record<string, " + type(spec, model, field, type.item()) + ">";
            // The event payload (D44). `unknown` rather than `any` on purpose: `any` would let
            // a caller read `event.data.whatever` with no complaint, which is exactly the
            // false confidence an opaque tree should not offer.
            case OBJECT -> "Record<string, unknown>";
            case UNKNOWN -> "unknown";
        };
        // A nullable field is `T | null` rather than optional. The two mean different things
        // on this platform and §9 leans on the difference: an absent field is one this
        // revision does not have, an explicitly null one is a value that was measured and
        // has no answer (D143's `successRate`).
        return type.nullable() ? base + " | null" : base;
    }

    /** Dates and instants stay strings: RFC 3339 text is what the wire carries. */
    private static String enumFor(SdkSpec spec, String model, String field) {
        for (SdkEnum declared : spec.enums()) {
            if (declared.owner().equals(model) && declared.field().equals(field)) {
                return declared.name();
            }
        }
        return "string";
    }

    // ── Rendering ───────────────────────────────────────────────────────────────────────

    /**
     * A property name, quoted only when it has to be. Every field in this document is a plain
     * identifier today; quoting defensively would put quotes around all of them forever.
     */
    private static String propertyName(String name) {
        return name.matches("[A-Za-z_$][A-Za-z0-9_$]*") ? name : quote(name);
    }

    private static String constantName(String typeName) {
        return typeName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT) + "_VALUES";
    }

    private static String enumDoc(SdkEnum declared) {
        String description = declared.description() != null ? declared.description() : "";
        return (description.isBlank() ? declared.owner() + "." + declared.field() : description)
                + "\n\nNew values may be added without a new API revision, so this type stays "
                + "open: treat an unrecognised value as one you do not handle rather than as an error.";
    }

    private static String doc(String text) {
        return renderDoc(text, "");
    }

    private static String indentedDoc(String text) {
        return renderDoc(text, "  ");
    }

    /**
     * A JSDoc block. {@code *&#47;} inside a description would close the comment early and
     * break the file, so it is neutralised — no description contains one today, and a
     * generator that only works while that stays true is a generator waiting to emit
     * uncompilable TypeScript.
     */
    private static String renderDoc(String text, String indent) {
        StringBuilder out = new StringBuilder(indent).append("/**\n");
        for (String line : text.replace("*/", "*\\/").split("\n", -1)) {
            out.append(indent).append(" *").append(line.isEmpty() ? "" : " " + line).append('\n');
        }
        return out.append(indent).append(" */\n").toString();
    }

    private static String quote(String value) {
        if (value == null) {
            return "''";
        }
        StringBuilder out = new StringBuilder("'");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '\'' -> out.append("\\'");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(character);
            }
        }
        return out.append('\'').toString();
    }
}
