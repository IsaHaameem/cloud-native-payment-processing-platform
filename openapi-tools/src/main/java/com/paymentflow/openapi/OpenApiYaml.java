package com.paymentflow.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

/**
 * Renders the merged document as the YAML that gets committed (M21.3).
 *
 * <p>Every setting here exists to make {@code git diff} on {@code docs/openapi.yaml} say
 * something true. The baseline's whole job is to be diffed — by a human in review, and by
 * M21.6's CI gate — so a formatting choice that turns one changed description into forty
 * changed lines is not cosmetic, it is the difference between a gate people read and a gate
 * people rubber-stamp.
 */
public final class OpenApiYaml {

    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder()
                    // No leading `---`. It is legal either way, and every OpenAPI document
                    // published anywhere omits it.
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    // Long descriptions stay on one line instead of being hard-wrapped at
                    // 80 columns. Wrapping re-flows every following line when a word is
                    // added, which is exactly the noise that hides a real change.
                    .disable(YAMLGenerator.Feature.SPLIT_LINES)
                    // Quote only what YAML requires quoting. Without this, every string
                    // value is quoted and the document reads as JSON with different
                    // punctuation.
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    // ...except strings that would read back as numbers, which MINIMIZE_QUOTES
                    // alone will happily emit bare. Caught by OpenApiYamlTest rather than
                    // reasoned about: the string "3.1" round-tripped as the *float* 3.1. The
                    // document does not contain that exact value today (`openapi` is "3.1.0",
                    // which has two dots and is safe), but `info.version` is a bare date and
                    // every future contract revision is another chance for a value that YAML
                    // types for itself. A consumer comparing `openapi == "3.1"` against a
                    // float silently gets the wrong answer, so the quoting is not left to
                    // whether today's literals happen to be safe.
                    .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
                    // Multi-line strings — every `description` in PublicApiDocument is one
                    // — render as literal blocks rather than one line with embedded `\n`.
                    // A reviewer can read the prose; a diff shows the changed sentence.
                    .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
                    .build())
            .findAndRegisterModules();

    private OpenApiYaml() {
    }

    /**
     * Serializes with a trailing newline, because a file without one makes the last line of
     * every diff render as a change.
     *
     * <p>Jackson 2's checked {@code JsonProcessingException} is wrapped rather than
     * propagated: the input is a tree that was just parsed from the fragments, so a
     * serialization failure here is a bug in this tool and not a condition any caller can
     * do anything about.
     */
    public static String write(JsonNode document) {
        String yaml;
        try {
            yaml = YAML.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not render the merged OpenAPI document as YAML", e);
        }
        return yaml.endsWith("\n") ? yaml : yaml + "\n";
    }

    /**
     * Parses a document back into a tree (M21.6).
     *
     * <p>The same mapper reads both {@code docs/openapi.yaml} and the services' JSON
     * fragments, because YAML is a superset of JSON and a single reader means the diff
     * cannot behave differently depending on which form it was handed. That matters: the
     * gate compares a committed YAML baseline against a document that started life as six
     * JSON fragments.
     */
    public static JsonNode read(String document) {
        try {
            return YAML.readTree(document);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("could not parse the OpenAPI document: " + e.getOriginalMessage(), e);
        }
    }
}
