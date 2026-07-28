package com.paymentflow.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rendering of {@code docs/openapi.yaml} (M21.3).
 *
 * <p>Each of these asserts a property of the <em>diff</em> rather than of the document.
 * The baseline exists to be reviewed by a human and diffed by CI (M21.6), so a rendering
 * choice that turns one changed sentence into forty changed lines is not cosmetic — it is
 * what decides whether the gate gets read or waved through.
 */
class OpenApiYamlTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void thereIsNoDocumentStartMarker() throws Exception {
        assertThat(write("""
                {"openapi": "3.1.0"}""")).doesNotStartWith("---");
    }

    @Test
    void longDescriptionsAreNotHardWrapped() throws Exception {
        // Wrapping re-flows every following line when a single word is added, which is
        // exactly the noise that hides the real change in a review.
        String description = "a ".repeat(120).trim();
        String yaml = write("{\"info\": {\"description\": \"" + description + "\"}}");

        assertThat(yaml.lines().filter(line -> line.contains("a a a")).findFirst().orElseThrow())
                .hasSizeGreaterThan(200);
    }

    @Test
    void multiLineProseRendersAsALiteralBlockRatherThanEscapedNewlines() throws Exception {
        // Every `description` PublicApiDocument sets is multi-line. As a single quoted
        // scalar with \n escapes it is unreadable in review and unreadable in a diff.
        String yaml = write("""
                {"info": {"description": "First paragraph.\\nSecond paragraph."}}""");

        assertThat(yaml).contains("|-").doesNotContain("\\n");
        assertThat(yaml).contains("First paragraph.").contains("Second paragraph.");
    }

    @Test
    void plainStringsAreNotGratuitouslyQuoted() throws Exception {
        assertThat(write("""
                {"openapi": "3.1.0", "info": {"title": "PaymentFlow API"}}"""))
                .contains("title: PaymentFlow API");
    }

    @Test
    void aVersionThatLooksLikeANumberOrADateStaysAString() throws Exception {
        // The two values most at risk from MINIMIZE_QUOTES. `3.1.0` is safe (two dots), but
        // the contract version is a bare date and `openapi: 3.1` would be a float — either
        // reading back as a non-string breaks every consumer that compares them. Asserted
        // by round-tripping rather than by looking for quotes in the text, because what
        // matters is what a YAML parser makes of it, not how it is spelled.
        JsonNode reparsed = YAML.readTree(write("""
                {"openapi": "3.1", "info": {"version": "2026-07-27"}}"""));

        assertThat(reparsed.path("openapi").getNodeType()).isEqualTo(JsonNodeType.STRING);
        assertThat(reparsed.path("info").path("version").getNodeType()).isEqualTo(JsonNodeType.STRING);
    }

    @Test
    void theFileEndsWithExactlyOneNewline() throws Exception {
        // Without it the last line of every diff renders as a change.
        String yaml = write("""
                {"openapi": "3.1.0"}""");

        assertThat(yaml).endsWith("\n").doesNotEndWith("\n\n");
    }

    @Test
    void renderingIsDeterministic() throws Exception {
        String document = """
                {"openapi": "3.1.0", "paths": {"/v1/payments": {"get": {"tags": ["Payments"]}}}}""";

        assertThat(write(document)).isEqualTo(write(document));
    }

    private static String write(String json) throws Exception {
        return OpenApiYaml.write(JSON.readTree(json));
    }
}
