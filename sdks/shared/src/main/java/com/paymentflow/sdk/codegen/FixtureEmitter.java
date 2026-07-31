package com.paymentflow.sdk.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.paymentflow.sdk.codegen.SdkSpec.SdkEnum;
import com.paymentflow.sdk.codegen.SdkSpec.SdkField;
import com.paymentflow.sdk.codegen.SdkSpec.SdkModel;
import com.paymentflow.sdk.codegen.SdkSpec.SdkOperation;
import com.paymentflow.sdk.codegen.SdkSpec.SdkParameter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emits the golden fixtures both SDKs' tests assert against (M22.1).
 *
 * <p><b>What these are for.</b> The blueprint requires the two SDKs' public behaviour to be
 * almost identical, and M22's risk table names divergence as the risk worth designing against.
 * A shared design document says they should agree; these files let a test say whether they do.
 * Each SDK loads them from {@code sdks/shared/fixtures} and asserts that its own generated
 * code matches — so "Node and Python describe the same contract" is checked in both languages,
 * against one artefact, rather than asserted in a review.
 *
 * <p>Language-neutral JSON on purpose. A fixture written as TypeScript could only be read by
 * one of the two consumers, which would leave the other free to drift in exactly the direction
 * nothing was watching.
 *
 * <p><b>Why a separate emitter rather than a dump of {@link SdkSpec}.</b> Serializing the
 * record directly would publish this generator's internal shape — {@code SdkType.Kind}, the
 * nullable flag, the reference spelling — as a contract two SDKs' tests depend on, and any
 * refactor of the reader would then break them for no reason a reader could see. What the
 * fixtures carry is the <em>contract's</em> facts: the revision, the operations, the enum
 * vocabularies, and each model's field list.
 */
final class FixtureEmitter {

    /** Where the fixtures land, relative to the repository root. */
    static final String DIRECTORY = "sdks/shared/fixtures";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /**
     * Jackson's default pretty printer indents with {@code DefaultIndenter.SYSTEM_LINEFEED},
     * which is CRLF on Windows — so the fixtures alone came out with different line endings
     * from the TypeScript and Python trees the same run writes. {@code .gitattributes} declares
     * {@code * text=auto eol=lf}, so a checkout puts LF on disk everywhere, and a Windows
     * contributor running {@code generateSdkSources} on a clean tree would have been left with
     * four modified files whose diff shows no change. Pinned to {@code \n} to match the other
     * two emitters, which build their output line by line.
     */
    private static final DefaultPrettyPrinter PRINTER = printer();

    private static DefaultPrettyPrinter printer() {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }

    private FixtureEmitter() {
    }

    static Map<String, String> emit(SdkSpec spec) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(DIRECTORY + "/contract.json", write(contract(spec)));
        files.put(DIRECTORY + "/enums.json", write(enums(spec)));
        files.put(DIRECTORY + "/models.json", write(models(spec)));
        files.put(DIRECTORY + "/operations.json", write(operations(spec)));
        return files;
    }

    private static ObjectNode contract(SdkSpec spec) {
        ObjectNode root = NODES.objectNode();
        root.put("apiVersion", spec.apiVersion());
        root.put("title", spec.title());
        root.put("baseUrl", spec.serverUrl());
        // Counts, so a fixture that silently emptied is a failing assertion in both SDKs
        // rather than a passing one over nothing — the "a test must not be able to pass by
        // failing to do the thing it claims" rule, applied to the fixture itself.
        root.put("operationCount", spec.operations().size());
        root.put("modelCount", spec.models().size());
        root.put("enumCount", spec.enums().size());
        return root;
    }

    private static ObjectNode enums(SdkSpec spec) {
        ObjectNode root = NODES.objectNode();
        for (SdkEnum declared : spec.enums()) {
            ArrayNode values = NODES.arrayNode();
            declared.values().forEach(values::add);
            root.set(declared.name(), values);
        }
        return root;
    }

    private static ObjectNode models(SdkSpec spec) {
        ObjectNode root = NODES.objectNode();
        for (SdkModel model : spec.models()) {
            ArrayNode fields = NODES.arrayNode();
            for (SdkField field : model.fields()) {
                fields.add(field.name());
            }
            root.set(model.name(), fields);
        }
        return root;
    }

    private static ObjectNode operations(SdkSpec spec) {
        ObjectNode root = NODES.objectNode();
        for (SdkOperation operation : spec.operations()) {
            ObjectNode node = NODES.objectNode();
            node.put("method", operation.method());
            node.put("path", operation.path());
            node.put("tag", operation.tag());
            node.put("successStatus", operation.success() == null ? "" : operation.success().status());
            node.put("hasRequestBody", operation.requestModel() != null);
            ArrayNode query = NODES.arrayNode();
            for (SdkParameter parameter : operation.parameters()) {
                if ("query".equals(parameter.in())) {
                    query.add(parameter.name());
                }
            }
            node.set("queryParameters", query);
            root.set(operation.id(), node);
        }
        return root;
    }

    /**
     * Pretty-printed with a trailing newline: these files are committed and reviewed, and a
     * one-line JSON document turns every change into one changed line that says nothing.
     */
    private static String write(JsonNode node) {
        try {
            return JSON.writer(PRINTER).writeValueAsString(node) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not render an SDK fixture as JSON", e);
        }
    }
}
