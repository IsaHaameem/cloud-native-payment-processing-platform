package com.paymentflow.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The entry point the Gradle {@code mergeOpenApi} and {@code verifyOpenApiBaseline} tasks
 * run (M21.3).
 *
 * <p>A {@code main} rather than a Gradle task class so the merge is testable as ordinary
 * Java, on this platform's normal testing conventions, instead of only through a build
 * invocation. The Gradle wiring in the root build file is then thin enough to read.
 *
 * <p>Usage:
 * <pre>
 *   OpenApiMergeCli --out &lt;file.yaml&gt; [--baseline &lt;file.yaml&gt;] &lt;fragment.json&gt;...
 * </pre>
 * With {@code --baseline}, the merged document is compared against the committed file and a
 * mismatch exits non-zero. That is the whole of the drift guard: the baseline is only a
 * promise if something checks that it still describes the running code.
 */
public final class OpenApiMergeCli {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenApiMergeCli() {
    }

    public static void main(String[] args) throws IOException {
        Path output = null;
        Path baseline = null;
        List<Path> fragments = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> output = Path.of(args[++i]);
                case "--baseline" -> baseline = Path.of(args[++i]);
                default -> fragments.add(Path.of(args[i]));
            }
        }

        if (output == null || fragments.isEmpty()) {
            System.err.println("usage: OpenApiMergeCli --out <file.yaml> [--baseline <file.yaml>] <fragment.json>...");
            System.exit(2);
            return;
        }

        List<OpenApiMerger.Fragment> parsed = new ArrayList<>();
        for (Path fragment : fragments) {
            if (!Files.exists(fragment)) {
                System.err.printf("missing fragment: %s%n", fragment);
                System.exit(1);
                return;
            }
            JsonNode document = JSON.readTree(Files.readString(fragment, StandardCharsets.UTF_8));
            // The file name, not the full path — it is what appears in every conflict
            // message, and an absolute Windows path in an error is noise.
            parsed.add(new OpenApiMerger.Fragment(fragment.getFileName().toString(), document));
        }

        String merged;
        try {
            // In the order given, deliberately: the caller lists the services in
            // documentation order, and that order is the merged document's tag order.
            merged = OpenApiYaml.write(new OpenApiMerger().merge(parsed));
        } catch (OpenApiMergeException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        Files.createDirectories(output.getParent());
        Files.writeString(output, merged, StandardCharsets.UTF_8);
        System.out.printf("merged %d fragments -> %s%n", parsed.size(), output);

        if (baseline != null && !matchesBaseline(baseline, merged)) {
            System.exit(1);
        }
    }

    /**
     * Compares against the committed baseline, reporting the first differing line rather
     * than only "they differ" — the merged document is thousands of lines and a developer
     * who has to diff it by hand to find out what changed will stop running this.
     */
    private static boolean matchesBaseline(Path baseline, String merged) throws IOException {
        if (!Files.exists(baseline)) {
            System.err.printf("""
                    The OpenAPI baseline %s does not exist.
                    Run `gradlew mergeOpenApi` and commit the generated file.%n""", baseline);
            return false;
        }

        // Normalised so a checkout with CRLF line endings does not read as a contract
        // change. The file is committed with LF; git may hand it back either way.
        List<String> expected = Files.readString(baseline, StandardCharsets.UTF_8).replace("\r\n", "\n").lines().toList();
        List<String> actual = merged.replace("\r\n", "\n").lines().toList();
        if (expected.equals(actual)) {
            System.out.printf("baseline %s is up to date%n", baseline);
            return true;
        }

        System.err.printf("""
                The generated OpenAPI document differs from the committed baseline %s.

                The public API changed. That is allowed - it is not allowed to happen
                without the baseline saying so, because the baseline is what M21.6 diffs
                for breaking changes and what M22's SDKs are generated from.

                Review the change, then run `gradlew mergeOpenApi` and commit the result.
                %s
                """, baseline, firstDifference(expected, actual));
        return false;
    }

    private static String firstDifference(List<String> expected, List<String> actual) {
        int limit = Math.min(expected.size(), actual.size());
        for (int i = 0; i < limit; i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                return "  first difference at line %d:%n    baseline:  %s%n    generated: %s"
                        .formatted(i + 1, expected.get(i), actual.get(i));
            }
        }
        return "  the documents share their first %d lines; the baseline has %d lines and the generated document has %d."
                .formatted(limit, expected.size(), actual.size());
    }
}
