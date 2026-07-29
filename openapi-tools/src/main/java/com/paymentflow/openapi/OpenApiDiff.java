package com.paymentflow.openapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Classifies the difference between two revisions of {@code docs/openapi.yaml} as additive
 * or breaking (M21.6, §5/M21 task 5).
 *
 * <p><b>What "breaking" means here.</b> §15 states the rule this implements: additive
 * changes — new endpoints, new fields, new enum values — ship unversioned, and anything a
 * correct client could notice as a removal or a change of meaning requires a new dated
 * revision. So the question this class answers is not "did the document change" (almost
 * every commit changes it) but "could a client written against the previous document still
 * be correct against this one".
 *
 * <p><b>Why a curated rule set rather than a generic tree diff.</b> §5/M21's own risk table
 * names the failure mode: a classifier with false negatives is worse than no classifier,
 * because it converts a review step into a rubber stamp. A generic diff cannot tell
 * {@code description: added} (documentation, and the whole of M21.7) from
 * {@code required: added} (a request every existing client now fails to make) — they are
 * both "a key appeared". Every rule below is therefore explicit about the direction of the
 * change, and anything this class has no rule for is reported as
 * <b>breaking</b>, not ignored. A false positive costs one conversation and one new rule; a
 * false negative ships a broken contract to every SDK generated from it.
 *
 * <p><b>What makes a breaking change acceptable.</b> Nothing about the change itself — only
 * the declaration. {@link Result#revisionDeclared()} is true when {@code info.version}
 * advanced, which on this platform means a new dated revision was cut and (per D156) a
 * transformation registered for the previous one. That is the "undeclared" in "fail CI on an
 * undeclared breaking change": the gate does not forbid breaking the API, it forbids
 * breaking it silently.
 *
 * <p>References are compared as strings and never followed. Component schemas are compared
 * once each, by name, at the top level — so a recursive schema ({@code PaymentResponse}
 * holds {@code RefundResponse}) is diffed exactly once and cannot cycle.
 */
public final class OpenApiDiff {

    /**
     * Keys whose value is prose or an illustration: changing one cannot change what any
     * request or response is required to contain.
     *
     * <p>This set is load-bearing for M21.7, which adds a summary, a description and an
     * example to every operation and a description to every schema property. Without it the
     * documentation milestone would read as several hundred breaking changes.
     */
    private static final Set<String> DOCUMENTATION_KEYS = Set.of(
            "summary", "description", "title", "example", "examples", "externalDocs",
            "deprecated", "readOnly", "writeOnly", "xml");

    /** Comparison directions for a schema, because the two are not symmetric. */
    private enum Direction {
        /** A body or parameter the client sends: tightening it rejects requests that worked. */
        REQUEST,
        /** A body the client reads: removing from it starves code that read the field. */
        RESPONSE
    }

    /** The classified difference between two documents. */
    public record Result(String previousRevision, String currentRevision, List<OpenApiChange> changes) {

        public Result {
            changes = List.copyOf(changes);
        }

        /**
         * True when the API revision advanced — the platform's one way of declaring that a
         * client may need to change. Any other value (including one that moved backwards)
         * is not a declaration.
         */
        public boolean revisionDeclared() {
            return previousRevision != null && currentRevision != null
                    && !previousRevision.equals(currentRevision)
                    && currentRevision.compareTo(previousRevision) > 0;
        }

        public List<OpenApiChange> breaking() {
            return changes.stream().filter(OpenApiChange::isBreaking).toList();
        }

        public List<OpenApiChange> additive() {
            return changes.stream().filter(change -> !change.isBreaking()).toList();
        }

        /** The gate's verdict: breaking changes are allowed only once a revision declares them. */
        public boolean isAcceptable() {
            return breaking().isEmpty() || revisionDeclared();
        }
    }

    /**
     * Compares {@code current} against {@code previous}, reporting every difference found
     * rather than stopping at the first — a contract change usually shows up in several
     * places at once, and a gate that reveals them one CI run at a time is a gate people
     * route around.
     */
    public Result compare(JsonNode previous, JsonNode current) {
        List<OpenApiChange> changes = new ArrayList<>();

        compareInfo(previous.path("info"), current.path("info"), changes);
        compareServers(previous.path("servers"), current.path("servers"), changes);
        compareDocumentSecurity(previous.path("security"), current.path("security"), changes);
        comparePaths(previous.path("paths"), current.path("paths"), changes);
        compareComponents(previous.path("components"), current.path("components"), changes);
        compareTags(previous.path("tags"), current.path("tags"), changes);

        Collections.sort(changes);
        return new Result(text(previous.path("info").path("version")),
                text(current.path("info").path("version")), changes);
    }

    // ── Document level ──────────────────────────────────────────────────────────────────

    /**
     * {@code info.version} is deliberately <em>not</em> reported as a change. It is the
     * declaration the rest of the report is judged against, and listing it among the
     * findings would invite reading "1 additive change" as though cutting a revision were
     * itself the edit.
     */
    private void compareInfo(JsonNode previous, JsonNode current, List<OpenApiChange> changes) {
        if (!previous.path("title").equals(current.path("title"))) {
            changes.add(OpenApiChange.additive("info.title",
                    "the document's title changed from %s to %s"
                            .formatted(previous.path("title"), current.path("title"))));
        }
    }

    /**
     * The server list is the host every generated SDK is pointed at. Moving it does not
     * break the shape of anything, but it does break every client that was built before the
     * move, which is the property this gate exists to defend.
     */
    private void compareServers(JsonNode previous, JsonNode current, List<OpenApiChange> changes) {
        if (!previous.equals(current)) {
            changes.add(OpenApiChange.breaking("servers",
                    "the published server list changed from %s to %s - every client built from the previous document calls the old host"
                            .formatted(previous, current)));
        }
    }

    /**
     * Document-level security. Adding a requirement makes previously-anonymous calls fail;
     * removing one only ever accepts more.
     */
    private void compareDocumentSecurity(JsonNode previous, JsonNode current, List<OpenApiChange> changes) {
        if (previous.equals(current)) {
            return;
        }
        if (previous.size() < current.size()) {
            changes.add(OpenApiChange.breaking("security",
                    "the document now requires more credentials than it did (%s -> %s)".formatted(previous, current)));
        } else {
            changes.add(OpenApiChange.additive("security",
                    "the document's credential requirement was relaxed (%s -> %s)".formatted(previous, current)));
        }
    }

    /** Tags name sections of the documentation site (M25); they promise nothing on the wire. */
    private void compareTags(JsonNode previous, JsonNode current, List<OpenApiChange> changes) {
        Set<String> before = tagNames(previous);
        Set<String> after = tagNames(current);
        for (String name : difference(before, after)) {
            changes.add(OpenApiChange.additive("tags." + name, "the tag was removed from the document"));
        }
        for (String name : difference(after, before)) {
            changes.add(OpenApiChange.additive("tags." + name, "a new tag was declared"));
        }
    }

    // ── Paths and operations ────────────────────────────────────────────────────────────

    private void comparePaths(JsonNode previous, JsonNode current, List<OpenApiChange> changes) {
        for (String path : difference(names(previous), names(current))) {
            changes.add(OpenApiChange.breaking("paths." + path,
                    "the path was removed - every client calling it now receives a 404"));
        }
        for (String path : difference(names(current), names(previous))) {
            changes.add(OpenApiChange.additive("paths." + path, "a new path was published"));
        }
        for (String path : intersection(names(previous), names(current))) {
            compareOperations("paths." + path, previous.path(path), current.path(path), changes);
        }
    }

    private void compareOperations(String location, JsonNode previous, JsonNode current,
                                   List<OpenApiChange> changes) {
        for (String verb : difference(names(previous), names(current))) {
            changes.add(OpenApiChange.breaking(location + "." + verb,
                    "the operation was removed - every client calling it now receives a 405"));
        }
        for (String verb : difference(names(current), names(previous))) {
            changes.add(OpenApiChange.additive(location + "." + verb, "a new operation was published"));
        }
        for (String verb : intersection(names(previous), names(current))) {
            compareOperation(location + "." + verb, previous.path(verb), current.path(verb), changes);
        }
    }

    private void compareOperation(String location, JsonNode previous, JsonNode current,
                                  List<OpenApiChange> changes) {
        // The operationId names the generated SDK method. Renaming it does not change a
        // single byte on the wire and breaks every caller's source code.
        if (!previous.path("operationId").equals(current.path("operationId"))) {
            changes.add(OpenApiChange.breaking(location + ".operationId",
                    "the operation id changed from %s to %s - this renames the method in every generated SDK"
                            .formatted(previous.path("operationId"), current.path("operationId"))));
        }
        compareOperationSecurity(location, previous, current, changes);
        compareParameters(location, previous.path("parameters"), current.path("parameters"), changes);
        compareRequestBody(location + ".requestBody", previous.path("requestBody"),
                current.path("requestBody"), changes);
        compareResponses(location + ".responses", previous.path("responses"),
                current.path("responses"), changes);
        reportUnclassifiedKeys(location, previous, current, changes,
                Set.of("operationId", "security", "parameters", "requestBody", "responses"));
    }

    /**
     * An operation that opted out of the document's credential requirement with
     * {@code security: []} and no longer does now rejects every call that worked. On this
     * platform that is exactly {@code GET /v1/test/cards} (§8.1).
     */
    private void compareOperationSecurity(String location, JsonNode previous, JsonNode current,
                                          List<OpenApiChange> changes) {
        boolean wasAnonymous = isAnonymous(previous);
        boolean isAnonymous = isAnonymous(current);
        if (wasAnonymous && !isAnonymous) {
            changes.add(OpenApiChange.breaking(location + ".security",
                    "the operation no longer accepts unauthenticated calls"));
        } else if (!wasAnonymous && isAnonymous) {
            changes.add(OpenApiChange.additive(location + ".security",
                    "the operation now also accepts unauthenticated calls"));
        }
    }

    private boolean isAnonymous(JsonNode operation) {
        JsonNode security = operation.path("security");
        return security.isArray() && security.isEmpty();
    }

    /**
     * Parameters are keyed by {@code in} + {@code name}, because {@code limit} in the query
     * and {@code limit} in a header are two different parameters that a positional
     * comparison would happily conflate.
     */
    private void compareParameters(String location, JsonNode previous, JsonNode current,
                                   List<OpenApiChange> changes) {
        Set<String> before = parameterKeys(previous);
        Set<String> after = parameterKeys(current);

        for (String key : difference(before, after)) {
            changes.add(OpenApiChange.breaking(location + ".parameters." + key,
                    "the parameter was removed - a client still sending it is now silently ignored"));
        }
        for (String key : difference(after, before)) {
            JsonNode added = parameter(current, key);
            if (added.path("required").asBoolean(false)) {
                changes.add(OpenApiChange.breaking(location + ".parameters." + key,
                        "a new *required* parameter was added - every existing call omits it"));
            } else {
                changes.add(OpenApiChange.additive(location + ".parameters." + key,
                        "a new optional parameter was added"));
            }
        }
        for (String key : intersection(before, after)) {
            String parameterLocation = location + ".parameters." + key;
            JsonNode wasParameter = parameter(previous, key);
            JsonNode isParameter = parameter(current, key);

            boolean wasRequired = wasParameter.path("required").asBoolean(false);
            boolean isRequired = isParameter.path("required").asBoolean(false);
            if (!wasRequired && isRequired) {
                changes.add(OpenApiChange.breaking(parameterLocation,
                        "the parameter became required - every call that omitted it now fails"));
            } else if (wasRequired && !isRequired) {
                changes.add(OpenApiChange.additive(parameterLocation, "the parameter became optional"));
            }
            // style/explode decide how a value is spelled on the wire. `deepObject` +
            // `explode` is `metadata[key]=value` (D142); anything else is a different
            // query string for the same parameter name.
            for (String key2 : List.of("style", "explode", "in", "allowEmptyValue")) {
                if (!wasParameter.path(key2).equals(isParameter.path(key2))) {
                    changes.add(OpenApiChange.breaking(parameterLocation + "." + key2,
                            "changed from %s to %s - the parameter is spelled differently on the wire"
                                    .formatted(wasParameter.path(key2), isParameter.path(key2))));
                }
            }
            compareSchema(parameterLocation + ".schema", wasParameter.path("schema"),
                    isParameter.path("schema"), Direction.REQUEST, changes);
            reportUnclassifiedKeys(parameterLocation, wasParameter, isParameter, changes,
                    Set.of("name", "in", "required", "schema", "style", "explode", "allowEmptyValue", "content"));
        }
    }

    private void compareRequestBody(String location, JsonNode previous, JsonNode current,
                                    List<OpenApiChange> changes) {
        if (previous.isMissingNode() && current.isMissingNode()) {
            return;
        }
        if (previous.isMissingNode()) {
            if (current.path("required").asBoolean(false)) {
                changes.add(OpenApiChange.breaking(location,
                        "the operation now requires a request body it previously took none of"));
            } else {
                changes.add(OpenApiChange.additive(location, "the operation now accepts an optional request body"));
            }
            return;
        }
        if (current.isMissingNode()) {
            changes.add(OpenApiChange.breaking(location,
                    "the request body was removed - what a client sends is no longer described"));
            return;
        }
        if (!previous.path("required").asBoolean(false) && current.path("required").asBoolean(false)) {
            changes.add(OpenApiChange.breaking(location,
                    "the request body became required - every call that omitted it now fails"));
        } else if (previous.path("required").asBoolean(false) && !current.path("required").asBoolean(false)) {
            changes.add(OpenApiChange.additive(location, "the request body became optional"));
        }
        compareContent(location + ".content", previous.path("content"), current.path("content"),
                Direction.REQUEST, changes);
    }

    private void compareResponses(String location, JsonNode previous, JsonNode current,
                                  List<OpenApiChange> changes) {
        for (String status : difference(names(previous), names(current))) {
            // Including the error statuses. An SDK generated from the previous document has
            // a typed case for this response; removing it deletes that case, which is a
            // source-level break even when the wire behaviour is unchanged.
            changes.add(OpenApiChange.breaking(location + "." + status,
                    "the documented response was removed"));
        }
        for (String status : difference(names(current), names(previous))) {
            changes.add(OpenApiChange.additive(location + "." + status, "a new response is documented"));
        }
        for (String status : intersection(names(previous), names(current))) {
            String responseLocation = location + "." + status;
            JsonNode before = previous.path(status);
            JsonNode after = current.path(status);
            compareContent(responseLocation + ".content", before.path("content"), after.path("content"),
                    Direction.RESPONSE, changes);
            compareResponseHeaders(responseLocation + ".headers", before.path("headers"),
                    after.path("headers"), changes);
            reportUnclassifiedKeys(responseLocation, before, after, changes,
                    Set.of("content", "headers", "links"));
        }
    }

    private void compareResponseHeaders(String location, JsonNode previous, JsonNode current,
                                        List<OpenApiChange> changes) {
        for (String header : difference(names(previous), names(current))) {
            changes.add(OpenApiChange.breaking(location + "." + header,
                    "the documented response header was removed"));
        }
        for (String header : difference(names(current), names(previous))) {
            changes.add(OpenApiChange.additive(location + "." + header, "a new response header is documented"));
        }
    }

    private void compareContent(String location, JsonNode previous, JsonNode current,
                                Direction direction, List<OpenApiChange> changes) {
        for (String mediaType : difference(names(previous), names(current))) {
            changes.add(OpenApiChange.breaking(location + "." + mediaType,
                    "the media type is no longer supported"));
        }
        for (String mediaType : difference(names(current), names(previous))) {
            changes.add(OpenApiChange.additive(location + "." + mediaType, "a new media type is supported"));
        }
        for (String mediaType : intersection(names(previous), names(current))) {
            compareSchema(location + "." + mediaType + ".schema",
                    previous.path(mediaType).path("schema"), current.path(mediaType).path("schema"),
                    direction, changes);
        }
    }

    // ── Components ──────────────────────────────────────────────────────────────────────

    private void compareComponents(JsonNode previous, JsonNode current, List<OpenApiChange> changes) {
        for (String section : union(names(previous), names(current))) {
            JsonNode before = previous.path(section);
            JsonNode after = current.path(section);
            for (String name : difference(names(before), names(after))) {
                changes.add(OpenApiChange.breaking("components.%s.%s".formatted(section, name),
                        "the component was removed - every $ref to it now dangles"));
            }
            for (String name : difference(names(after), names(before))) {
                changes.add(OpenApiChange.additive("components.%s.%s".formatted(section, name),
                        "a new component was published"));
            }
            for (String name : intersection(names(before), names(after))) {
                String location = "components.%s.%s".formatted(section, name);
                if ("schemas".equals(section)) {
                    // A component schema is reachable from both directions - the same
                    // `CreatePaymentRequest` is written by a client and the same
                    // `PaymentResponse` is read by one - so it is judged under whichever
                    // rule is stricter for each kind of edit. RESPONSE covers property
                    // removal; the `required` rule below is direction-independent.
                    compareSchema(location, before.path(name), after.path(name), Direction.RESPONSE, changes);
                } else if (!before.path(name).equals(after.path(name))) {
                    // securitySchemes, parameters, responses, headers - a shared definition
                    // that changed shape changes it for every reference at once.
                    changes.add(OpenApiChange.breaking(location,
                            "the shared component definition changed, which changes it for every $ref to it"));
                }
            }
        }
    }

    // ── Schemas ─────────────────────────────────────────────────────────────────────────

    /**
     * The rule set that does most of the work. Note the asymmetries, which are the whole
     * reason a generic diff cannot do this job:
     *
     * <ul>
     *   <li>A property <em>added</em> to a response is additive; a property <em>removed</em>
     *       from one starves code that read it.</li>
     *   <li>An entry added to {@code required} is breaking in both directions — for a
     *       request because every existing call omits it, for a response because a client
     *       may have been generated with the field nullable.</li>
     *   <li>An enum value <em>added</em> is additive by explicit policy (§9: clients must
     *       tolerate unknown enum values); one <em>removed</em> is breaking, because the API
     *       no longer accepts or produces something it documented.</li>
     *   <li>A constraint tightened is breaking; loosened is additive.</li>
     * </ul>
     */
    private void compareSchema(String location, JsonNode previous, JsonNode current,
                               Direction direction, List<OpenApiChange> changes) {
        if (previous.equals(current)) {
            return;
        }
        if (previous.isMissingNode()) {
            changes.add(OpenApiChange.additive(location, "a schema was added where none was described"));
            return;
        }
        if (current.isMissingNode()) {
            changes.add(OpenApiChange.breaking(location,
                    "the schema was removed - the payload is no longer described"));
            return;
        }

        // $ref is compared as a string and never followed: the target is a component, and
        // components are compared once each by name. Following it here would diff the same
        // schema once per reference and recurse forever on PaymentResponse -> RefundResponse.
        if (!previous.path("$ref").equals(current.path("$ref"))) {
            changes.add(OpenApiChange.breaking(location + ".$ref",
                    "the payload type changed from %s to %s"
                            .formatted(previous.path("$ref"), current.path("$ref"))));
            return;
        }

        compareSchemaType(location, previous, current, changes);
        compareEnum(location, previous, current, changes);
        compareRequired(location, previous, current, changes);
        compareConstraints(location, previous, current, changes);
        compareProperties(location, previous, current, direction, changes);

        compareSchema(location + ".items", previous.path("items"), current.path("items"), direction, changes);
        compareAdditionalProperties(location, previous, current, direction, changes);

        reportUnclassifiedKeys(location, previous, current, changes,
                Set.of("$ref", "type", "format", "enum", "required", "properties", "items",
                        "additionalProperties", "maxLength", "minLength", "maximum", "minimum",
                        "exclusiveMaximum", "exclusiveMinimum", "pattern", "maxItems", "minItems",
                        "default", "allOf", "anyOf", "oneOf", "discriminator", "const", "uniqueItems",
                        "multipleOf", "contentEncoding", "contentMediaType"));
    }

    private void compareSchemaType(String location, JsonNode previous, JsonNode current,
                                   List<OpenApiChange> changes) {
        for (String key : List.of("type", "format", "pattern", "const", "default", "discriminator")) {
            if (!previous.path(key).equals(current.path(key))) {
                changes.add(OpenApiChange.breaking(location + "." + key,
                        "changed from %s to %s".formatted(previous.path(key), current.path(key))));
            }
        }
        // Composition keywords are compared whole. This platform's generated document uses
        // none of them today; reporting a change to one as breaking rather than ignoring it
        // is the fail-safe direction for the day springdoc starts emitting them.
        for (String key : List.of("allOf", "anyOf", "oneOf")) {
            if (!previous.path(key).equals(current.path(key))) {
                changes.add(OpenApiChange.breaking(location + "." + key,
                        "the schema composition changed, which this diff cannot narrow further"));
            }
        }
    }

    private void compareEnum(String location, JsonNode previous, JsonNode current, List<OpenApiChange> changes) {
        Set<String> before = values(previous.path("enum"));
        Set<String> after = values(current.path("enum"));
        for (String value : difference(before, after)) {
            changes.add(OpenApiChange.breaking(location + ".enum",
                    "the value `%s` is no longer part of the enumeration".formatted(value)));
        }
        for (String value : difference(after, before)) {
            // Additive by policy, not by accident: §9 requires clients to tolerate unknown
            // enum values precisely so that this stays a non-event.
            changes.add(OpenApiChange.additive(location + ".enum",
                    "the value `%s` was added to the enumeration".formatted(value)));
        }
    }

    private void compareRequired(String location, JsonNode previous, JsonNode current,
                                 List<OpenApiChange> changes) {
        Set<String> before = values(previous.path("required"));
        Set<String> after = values(current.path("required"));
        for (String property : difference(after, before)) {
            changes.add(OpenApiChange.breaking(location + ".required",
                    "`%s` became required".formatted(property)));
        }
        for (String property : difference(before, after)) {
            changes.add(OpenApiChange.additive(location + ".required",
                    "`%s` is no longer required".formatted(property)));
        }
    }

    /** Tightening rejects payloads that were legal; loosening only ever accepts more. */
    private void compareConstraints(String location, JsonNode previous, JsonNode current,
                                    List<OpenApiChange> changes) {
        compareBound(location, "maxLength", previous, current, false, changes);
        compareBound(location, "maxItems", previous, current, false, changes);
        compareBound(location, "maximum", previous, current, false, changes);
        compareBound(location, "exclusiveMaximum", previous, current, false, changes);
        compareBound(location, "minLength", previous, current, true, changes);
        compareBound(location, "minItems", previous, current, true, changes);
        compareBound(location, "minimum", previous, current, true, changes);
        compareBound(location, "exclusiveMinimum", previous, current, true, changes);
    }

    /**
     * @param tightenedWhenLarger true for lower bounds ({@code minLength} rising is a
     *                            tightening) and false for upper bounds ({@code maxLength}
     *                            falling is). Introducing a bound where there was none is a
     *                            tightening either way; removing one is a relaxation.
     */
    private void compareBound(String location, String key, JsonNode previous, JsonNode current,
                              boolean tightenedWhenLarger, List<OpenApiChange> changes) {
        JsonNode before = previous.path(key);
        JsonNode after = current.path(key);
        if (before.equals(after)) {
            return;
        }
        String detail = "changed from %s to %s".formatted(
                before.isMissingNode() ? "unbounded" : before,
                after.isMissingNode() ? "unbounded" : after);

        boolean tightened;
        if (before.isMissingNode()) {
            tightened = true;
        } else if (after.isMissingNode()) {
            tightened = false;
        } else {
            tightened = tightenedWhenLarger
                    ? after.asDouble() > before.asDouble()
                    : after.asDouble() < before.asDouble();
        }

        changes.add(tightened
                ? OpenApiChange.breaking(location + "." + key, detail + " - values that were legal are now rejected")
                : OpenApiChange.additive(location + "." + key, detail));
    }

    private void compareProperties(String location, JsonNode previous, JsonNode current,
                                   Direction direction, List<OpenApiChange> changes) {
        JsonNode before = previous.path("properties");
        JsonNode after = current.path("properties");
        Set<String> required = values(current.path("required"));

        for (String property : difference(names(before), names(after))) {
            changes.add(OpenApiChange.breaking(location + ".properties." + property,
                    direction == Direction.RESPONSE
                            ? "the field was removed - code reading it now finds nothing"
                            : "the field is no longer accepted"));
        }
        for (String property : difference(names(after), names(before))) {
            if (required.contains(property)) {
                // Caught by compareRequired too, but stated here as well because a reader
                // scanning the properties section should not have to correlate two entries
                // to see that a mandatory field appeared.
                changes.add(OpenApiChange.breaking(location + ".properties." + property,
                        "a new *required* field was added"));
            } else {
                changes.add(OpenApiChange.additive(location + ".properties." + property,
                        "a new optional field was added"));
            }
        }
        for (String property : intersection(names(before), names(after))) {
            compareSchema(location + ".properties." + property, before.path(property),
                    after.path(property), direction, changes);
        }
    }

    private void compareAdditionalProperties(String location, JsonNode previous, JsonNode current,
                                             Direction direction, List<OpenApiChange> changes) {
        JsonNode before = previous.path("additionalProperties");
        JsonNode after = current.path("additionalProperties");
        if (before.equals(after)) {
            return;
        }
        if (before.isObject() && after.isObject()) {
            compareSchema(location + ".additionalProperties", before, after, direction, changes);
            return;
        }
        // false -> true accepts more; anything else (true -> false, or a schema appearing
        // where a boolean was) narrows what may be sent.
        boolean loosened = before.isBoolean() && !before.asBoolean() && !(after.isBoolean() && !after.asBoolean());
        changes.add(loosened
                ? OpenApiChange.additive(location + ".additionalProperties",
                        "changed from %s to %s".formatted(before, after))
                : OpenApiChange.breaking(location + ".additionalProperties",
                        "changed from %s to %s - the object accepts less than it did".formatted(before, after)));
    }

    // ── The fail-safe ───────────────────────────────────────────────────────────────────

    /**
     * Reports any key this diff has no rule for as breaking.
     *
     * <p>This is the class's most important five lines. The realistic way a
     * breaking-change gate fails is not a wrong rule but an absent one — springdoc emits a
     * keyword nobody anticipated, the walker never looks at it, and the gate reports "no
     * breaking changes" about a document that lost a field. Defaulting to breaking makes
     * that failure loud and one rule away from fixed, instead of silent.
     *
     * <p>{@link #DOCUMENTATION_KEYS} is the exemption, and it is deliberately a list of
     * prose and illustrations only.
     */
    private void reportUnclassifiedKeys(String location, JsonNode previous, JsonNode current,
                                        List<OpenApiChange> changes, Set<String> handled) {
        for (String key : union(names(previous), names(current))) {
            if (handled.contains(key) || DOCUMENTATION_KEYS.contains(key)) {
                continue;
            }
            if (!previous.path(key).equals(current.path(key))) {
                changes.add(OpenApiChange.breaking(location + "." + key,
                        "`%s` changed and this diff has no rule for it, so it is treated as breaking until one is written"
                                .formatted(key)));
            }
        }
    }

    // ── Tree helpers ────────────────────────────────────────────────────────────────────

    /** Property names in document order. {@code properties()} rather than the 3.x-only
     *  {@code propertyNames()} — this module is on the Jackson 2 line (see the build file). */
    private static Set<String> names(JsonNode node) {
        if (!node.isObject()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    private static Set<String> values(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static Set<String> tagNames(JsonNode tags) {
        Set<String> names = new LinkedHashSet<>();
        tags.forEach(tag -> names.add(tag.path("name").asText()));
        return names;
    }

    private static Set<String> parameterKeys(JsonNode parameters) {
        Set<String> keys = new LinkedHashSet<>();
        parameters.forEach(parameter ->
                keys.add(parameter.path("in").asText() + ":" + parameter.path("name").asText()));
        return keys;
    }

    private static JsonNode parameter(JsonNode parameters, String key) {
        for (JsonNode parameter : parameters) {
            if (key.equals(parameter.path("in").asText() + ":" + parameter.path("name").asText())) {
                return parameter;
            }
        }
        throw new IllegalStateException("no parameter " + key);
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.addAll(right);
        return result;
    }
}
