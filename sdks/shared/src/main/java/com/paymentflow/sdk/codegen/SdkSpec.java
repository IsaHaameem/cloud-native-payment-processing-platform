package com.paymentflow.sdk.codegen;

import java.util.List;

/**
 * The language-neutral shape of the public API, read once from {@code docs/openapi.yaml} and
 * emitted twice (M22.1).
 *
 * <p><b>Why an intermediate representation rather than two walks over the document.</b> The
 * blueprint's requirement is that the two SDKs are <em>genuinely</em> equivalent rather than
 * accidentally similar, and M22's risk table names divergence as the thing to design against.
 * Two emitters reading the OpenAPI tree directly would each make their own decisions about
 * what a nullable union means, which enums exist, and how an operation's name is derived —
 * and the day they disagree, nothing would notice, because each would still produce a valid
 * package. With one reader, a disagreement is impossible by construction: the emitters are
 * handed the same answers and can only differ in how they spell them.
 *
 * <p>It is also what makes the shared golden fixtures meaningful. The fixtures are this
 * record, serialized; both SDKs' tests assert their generated code against them, so "the two
 * languages agree" is a test rather than a claim.
 *
 * <p><b>What is deliberately not modelled.</b> Anything the ergonomic layer owns — retries,
 * pagination, idempotency, the error hierarchy. Those are hand-written per language (the
 * blueprint's "generated models + handwritten SDK logic"), because they are exactly what
 * generators do badly. This record describes the contract's <em>data</em>, and the operation
 * metadata a hand-written client needs in order to address it.
 */
public record SdkSpec(
        String apiVersion,
        String title,
        String serverUrl,
        List<SdkEnum> enums,
        List<SdkModel> models,
        List<SdkOperation> operations) {

    public SdkSpec {
        enums = List.copyOf(enums);
        models = List.copyOf(models);
        operations = List.copyOf(operations);
    }

    /**
     * A named set of string values a field may take.
     *
     * <p>Every enum in this document is declared inline on a property rather than as a named
     * component, so the name is derived: {@code PaymentResponse.mode} becomes
     * {@code PaymentResponseMode}. Derivation rather than a hand-maintained mapping, because
     * a mapping is one more thing to forget when a field is added.
     *
     * <p><b>The values are documentation, not a closed type.</b> §9 requires clients to
     * tolerate enum values they do not know — that is what makes "a new enum value is
     * additive" true rather than aspirational — so both emitters widen these to the language's
     * plain string type and expose the known values as a companion constant. An SDK that
     * threw on an unrecognised status would convert the platform's safest kind of change into
     * an outage in every integrator's code at once.
     */
    public record SdkEnum(String name, String owner, String field, String description, List<String> values) {
        public SdkEnum {
            values = List.copyOf(values);
        }
    }

    /** A component schema: one object shape a caller sends or receives. */
    public record SdkModel(String name, String description, List<SdkField> fields) {
        public SdkModel {
            fields = List.copyOf(fields);
        }
    }

    /** One property of a model. */
    public record SdkField(String name, SdkType type, boolean required, String description) {
    }

    /**
     * One published operation — enough for a hand-written client to address it, and no more.
     *
     * <p>{@code id} is the document's {@code operationId}, which M21.7 made unique across the
     * merged document precisely so that it could name a method here.
     */
    public record SdkOperation(
            String id,
            String method,
            String path,
            String tag,
            String summary,
            List<SdkParameter> parameters,
            String requestModel,
            SdkResponse success) {

        public SdkOperation {
            parameters = List.copyOf(parameters);
        }
    }

    /** A query, path or header parameter. */
    public record SdkParameter(String name, String in, boolean required, SdkType type, String description) {
    }

    /**
     * The query parameters an operation accepts, in the document's order.
     *
     * <p>Here rather than in each emitter so that the three of them cannot answer differently.
     * The fixtures exist to prove Node and Python describe the same contract; a fixture derived
     * by its own filter could agree with neither.
     */
    public static List<String> queryParameters(SdkOperation operation) {
        return operation.parameters().stream()
                .filter(parameter -> "query".equals(parameter.in()))
                .map(SdkParameter::name)
                .toList();
    }

    /**
     * The header parameters an operation marks {@code required}.
     *
     * <p>Today this is {@code Idempotency-Key} on the five payment mutations, and nothing else:
     * every operation documents {@code PaymentFlow-Version} and {@code X-Correlation-Id}, and
     * both are optional. A hand-written client needs the distinction because it must generate a
     * key for exactly those operations — once per logical call, reused across every retry —
     * and it must not invent one for an endpoint the platform does not deduplicate on.
     */
    public static List<String> requiredHeaders(SdkOperation operation) {
        return operation.parameters().stream()
                .filter(parameter -> "header".equals(parameter.in()) && parameter.required())
                .map(SdkParameter::name)
                .toList();
    }

    /** The operation's documented success: its status and, when it has one, its body model. */
    public record SdkResponse(String status, String model) {
    }

    /**
     * A field or parameter type, flattened to the handful of shapes this document actually
     * uses.
     *
     * <p>Narrow on purpose. {@link Kind#UNKNOWN} exists so that a construct nobody
     * anticipated produces a permissive type in both languages rather than a generator crash
     * or, worse, a confidently wrong one — but {@link SdkSpecReader} reports every use of it,
     * so "the document grew a keyword" is visible rather than silently absorbed.
     */
    public record SdkType(Kind kind, String reference, SdkType item, boolean nullable) {

        public enum Kind {
            STRING, INTEGER, NUMBER, BOOLEAN,
            /** {@code format: date} — a calendar day, with no time and no zone. */
            DATE,
            /** {@code format: date-time} — an RFC 3339 instant. */
            DATE_TIME,
            /** {@code format: uuid}. */
            UUID,
            /** A {@code $ref} to another model; {@link SdkType#reference()} names it. */
            REFERENCE,
            /** An array; {@link SdkType#item()} is the element type. */
            ARRAY,
            /** An object with {@code additionalProperties} — a string map, in this document. */
            MAP,
            /** A free-form object: the event payload, whose shape is deliberately open. */
            OBJECT,
            /** Anything this reader has no rule for. Reported, never silently emitted. */
            UNKNOWN
        }

        public static SdkType of(Kind kind, boolean nullable) {
            return new SdkType(kind, null, null, nullable);
        }

        public static SdkType reference(String name, boolean nullable) {
            return new SdkType(Kind.REFERENCE, name, null, nullable);
        }

        public static SdkType array(SdkType item, boolean nullable) {
            return new SdkType(Kind.ARRAY, null, item, nullable);
        }

        public static SdkType map(SdkType value, boolean nullable) {
            return new SdkType(Kind.MAP, null, value, nullable);
        }
    }
}
