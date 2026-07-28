package com.paymentflow.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Merges the six per-service OpenAPI fragments into the one document the platform
 * publishes (M21.3, §5/M21 task 2).
 *
 * <p><b>Why this is a merge and not a concatenation.</b> Each service describes only the
 * slice of {@code /v1} it serves, but all six describe the <em>same API</em> — same title,
 * same date-based revision, same server, same {@code SecretKey} scheme (D149). The
 * interesting cases are therefore the collisions: two services publishing the same schema
 * name, or the same tag, or the same path. Some of those are correct and must be
 * deduplicated ({@code ApiError} will appear in all six once M21.4 documents error
 * responses); the rest are contract bugs and must stop the build. A merge that silently
 * took whichever fragment it read last would turn the second kind into the first.
 *
 * <p><b>Trees rather than swagger's object model.</b> The fragments are parsed as plain
 * JSON and merged structurally. Round-tripping through {@code io.swagger.v3.oas.models}
 * would re-serialize from a model that drops anything it has no field for — vendor
 * extensions, and any 3.1 keyword swagger-core has not caught up with — so the published
 * document would silently differ from what the services actually serve. Structural
 * equality on {@link JsonNode} is also exactly the comparison this needs: two schema
 * definitions are the same definition iff they are the same tree.
 */
public final class OpenApiMerger {

    /** One service's contribution: where it came from, and what it said. */
    public record Fragment(String source, JsonNode document) {
    }

    /**
     * The document-level keys every fragment must agree on, because the merged document
     * has exactly one of each. {@code openapi} is included deliberately: a fragment
     * generated at 3.0 while the others are at 3.1 changes the schema dialect, which is
     * the kind of difference that produces a valid-looking document and broken generated
     * SDKs.
     */
    private static final List<String> SHARED_KEYS = List.of("openapi", "info", "servers", "security");

    /**
     * Merges the fragments <em>in the order given</em>, which is the caller's decision and
     * not this class's. Only the tag list depends on it — paths and components are sorted
     * by name so their order cannot drift — and the tag list is the documentation site's
     * navigation (M25), where "Payments" belongs above "Balance" for reasons no sort knows.
     * The build file states that order explicitly; sorting here would silently overrule it.
     *
     * @throws OpenApiMergeException if the fragments disagree in any way that the merged
     *                               document cannot represent. Every disagreement found is
     *                               reported, not just the first — a contract drift usually
     *                               shows up in several places at once, and fixing them one
     *                               build at a time is how a merge step becomes hated.
     */
    public ObjectNode merge(List<Fragment> fragments) {
        if (fragments.isEmpty()) {
            throw new OpenApiMergeException(List.of("no fragments to merge"));
        }

        List<String> conflicts = new ArrayList<>();
        ObjectNode merged = JsonNodeFactory.instance.objectNode();

        mergeSharedKeys(fragments, merged, conflicts);
        mergePaths(fragments, merged, conflicts);
        mergeTags(fragments, merged, conflicts);
        mergeComponents(fragments, merged, conflicts);

        if (!conflicts.isEmpty()) {
            throw new OpenApiMergeException(conflicts);
        }
        return merged;
    }

    /**
     * The shared half of the contract. This is the assertion {@code PublicApiDocumentTest}
     * makes about the source and this makes about the artefacts: common-lib proves there is
     * one implementation, this proves the six documents actually served carry it. A service
     * that added a stray {@code @OpenAPIDefinition} would pass the former and fail here.
     */
    private void mergeSharedKeys(List<Fragment> fragments, ObjectNode merged, List<String> conflicts) {
        Fragment first = fragments.getFirst();
        for (String key : SHARED_KEYS) {
            JsonNode expected = first.document().path(key);
            for (Fragment other : fragments.subList(1, fragments.size())) {
                JsonNode actual = other.document().path(key);
                if (!expected.equals(actual)) {
                    conflicts.add("`%s` differs between %s and %s - every fragment describes the same API, so this key must be identical in all of them.%n  %s: %s%n  %s: %s"
                            .formatted(key, first.source(), other.source(),
                                    first.source(), expected, other.source(), actual));
                }
            }
            if (!expected.isMissingNode()) {
                merged.set(key, expected.deepCopy());
            }
        }
    }

    /**
     * Path items are unioned. A path appearing in two fragments is always a bug: the public
     * tier is routed by prefix to exactly one service, so two services claiming the same
     * path item means either a copy-pasted mapping or a routing mistake — and the merged
     * document could only describe one of them.
     */
    private void mergePaths(List<Fragment> fragments, ObjectNode merged, List<String> conflicts) {
        Map<String, JsonNode> paths = new TreeMap<>();
        Map<String, String> owner = new LinkedHashMap<>();

        for (Fragment fragment : fragments) {
            JsonNode fragmentPaths = fragment.document().path("paths");
            for (Map.Entry<String, JsonNode> entry : fragmentPaths.properties()) {
                String path = entry.getKey();
                String previous = owner.get(path);
                if (previous != null) {
                    conflicts.add("path `%s` is published by both %s and %s - a public path belongs to exactly one service."
                            .formatted(path, previous, fragment.source()));
                    continue;
                }
                owner.put(path, fragment.source());
                paths.put(path, entry.getValue().deepCopy());
            }
        }

        ObjectNode mergedPaths = merged.putObject("paths");
        paths.forEach(mergedPaths::set);
    }

    /**
     * Tags are unioned by name, keeping first-declaration order so the documentation site's
     * navigation (M25) is the order the services were merged in rather than alphabetical.
     * The same tag name carrying two different descriptions is a conflict: it renders as one
     * section on the site, so one of the two descriptions would simply be lost.
     */
    private void mergeTags(List<Fragment> fragments, ObjectNode merged, List<String> conflicts) {
        Map<String, JsonNode> tags = new LinkedHashMap<>();
        Map<String, String> owner = new LinkedHashMap<>();

        for (Fragment fragment : fragments) {
            for (JsonNode tag : fragment.document().path("tags")) {
                String name = tag.path("name").asText();
                JsonNode existing = tags.get(name);
                if (existing == null) {
                    tags.put(name, tag.deepCopy());
                    owner.put(name, fragment.source());
                } else if (!existing.equals(tag)) {
                    conflicts.add("tag `%s` is declared differently by %s and %s - one tag renders as one section, so one description would be lost.%n  %s: %s%n  %s: %s"
                            .formatted(name, owner.get(name), fragment.source(),
                                    owner.get(name), existing, fragment.source(), tag));
                }
            }
        }

        if (!tags.isEmpty()) {
            ArrayNode mergedTags = merged.putArray("tags");
            tags.values().forEach(mergedTags::add);
        }
    }

    /**
     * Components are merged per section ({@code schemas}, {@code securitySchemes},
     * {@code parameters}, …) rather than only for schemas, so a section added by a later
     * milestone is merged rather than dropped on the floor.
     *
     * <p>This is where deduplication earns its place. {@code SecretKey} is defined
     * identically by all six fragments today because {@code PublicApiDocument} builds it,
     * and M21.4 will add {@code ApiError} to all six the same way; both collapse to one
     * definition. A name defined <em>differently</em> by two services is the failure this
     * exists to catch — the merged document can hold only one {@code #/components/schemas/X},
     * so every {@code $ref} in the other service's half would silently start pointing at
     * someone else's type.
     */
    private void mergeComponents(List<Fragment> fragments, ObjectNode merged, List<String> conflicts) {
        Map<String, Map<String, JsonNode>> sections = new TreeMap<>();
        Map<String, String> owner = new LinkedHashMap<>();

        for (Fragment fragment : fragments) {
            JsonNode components = fragment.document().path("components");
            for (Map.Entry<String, JsonNode> section : components.properties()) {
                String sectionName = section.getKey();
                Map<String, JsonNode> target =
                        sections.computeIfAbsent(sectionName, unused -> new TreeMap<>());

                for (Map.Entry<String, JsonNode> entry : section.getValue().properties()) {
                    String name = entry.getKey();
                    String key = sectionName + "/" + name;
                    JsonNode existing = target.get(name);

                    if (existing == null) {
                        target.put(name, entry.getValue().deepCopy());
                        owner.put(key, fragment.source());
                    } else if (!existing.equals(entry.getValue())) {
                        conflicts.add("`components.%s.%s` is defined differently by %s and %s - the merged document can hold only one, so every $ref to it in the other service's half would point at the wrong type."
                                .formatted(sectionName, name, owner.get(key), fragment.source()));
                    }
                    // Identical definitions are the deduplication case: nothing to do.
                }
            }
        }

        if (sections.isEmpty()) {
            return;
        }
        ObjectNode mergedComponents = merged.putObject("components");
        sections.forEach((sectionName, entries) -> {
            ObjectNode sectionNode = mergedComponents.putObject(sectionName);
            entries.forEach(sectionNode::set);
        });
    }

}
