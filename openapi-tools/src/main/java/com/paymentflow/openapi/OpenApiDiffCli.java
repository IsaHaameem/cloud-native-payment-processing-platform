package com.paymentflow.openapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The breaking-change gate CI runs (M21.6, §5/M21 task 5).
 *
 * <p>Compares the published {@code docs/openapi.yaml} against the copy on the branch being
 * merged into, classifies every difference, and exits non-zero when a breaking change was
 * made without declaring a new API revision.
 *
 * <pre>
 *   OpenApiDiffCli --previous &lt;file.yaml&gt; --current &lt;file.yaml&gt; [--summary &lt;file.md&gt;]
 * </pre>
 *
 * <p><b>Why the previous document comes from a file rather than from git.</b> Extracting it
 * is one {@code git show} in the workflow, and keeping git out of here means the gate is
 * runnable by hand against any two documents — which is how it was demonstrated failing
 * before it was trusted, and how a developer checks a change before pushing it.
 *
 * <p><b>A missing previous document is not a failure.</b> The first commit to publish a
 * baseline has nothing to be compared against, and a gate that failed closed on that would
 * have to be disabled exactly once — after which it stays disabled.
 */
public final class OpenApiDiffCli {

    private OpenApiDiffCli() {
    }

    public static void main(String[] args) throws IOException {
        Path previousPath = null;
        Path currentPath = null;
        Path summaryPath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--previous" -> previousPath = Path.of(args[++i]);
                case "--current" -> currentPath = Path.of(args[++i]);
                case "--summary" -> summaryPath = Path.of(args[++i]);
                default -> {
                    System.err.printf("unrecognized argument: %s%n", args[i]);
                    System.exit(2);
                    return;
                }
            }
        }

        if (previousPath == null || currentPath == null) {
            System.err.println("usage: OpenApiDiffCli --previous <file.yaml> --current <file.yaml> [--summary <file.md>]");
            System.exit(2);
            return;
        }

        if (!Files.exists(currentPath)) {
            System.err.printf("the current OpenAPI document %s does not exist.%n", currentPath);
            System.exit(1);
            return;
        }
        if (!Files.exists(previousPath) || Files.size(previousPath) == 0) {
            System.out.printf("""
                    No previous OpenAPI document at %s - nothing to compare against.
                    This is expected on the commit that first publishes the baseline.%n""", previousPath);
            return;
        }

        JsonNode previous = OpenApiYaml.read(Files.readString(previousPath, StandardCharsets.UTF_8));
        JsonNode current = OpenApiYaml.read(Files.readString(currentPath, StandardCharsets.UTF_8));
        OpenApiDiff.Result result = new OpenApiDiff().compare(previous, current);

        String report = report(result);
        System.out.print(report);
        if (summaryPath != null) {
            Files.createDirectories(summaryPath.toAbsolutePath().getParent());
            Files.writeString(summaryPath, report, StandardCharsets.UTF_8);
        }

        if (!result.isAcceptable()) {
            System.exit(1);
        }
    }

    /**
     * One report for both outcomes, written to be read by whoever the gate stopped rather
     * than by whoever wrote it. The additive changes are listed too — a reviewer needs to
     * see that the field they expected to be additive was in fact classified that way, and a
     * gate that only speaks when it is angry teaches people that silence means nothing
     * happened.
     */
    private static String report(OpenApiDiff.Result result) {
        StringBuilder report = new StringBuilder();
        List<OpenApiChange> breaking = result.breaking();
        List<OpenApiChange> additive = result.additive();

        report.append("OpenAPI contract diff  %s -> %s%n".formatted(
                result.previousRevision(), result.currentRevision()));
        report.append("  %d breaking, %d additive%n%n".formatted(breaking.size(), additive.size()));

        if (result.changes().isEmpty()) {
            report.append("  The published contract is unchanged.\n");
            return report.toString();
        }

        if (!breaking.isEmpty()) {
            report.append("BREAKING\n");
            breaking.forEach(change -> report.append("  %s%n      %s%n".formatted(change.location(), change.detail())));
            report.append('\n');
        }
        if (!additive.isEmpty()) {
            report.append("ADDITIVE\n");
            additive.forEach(change -> report.append("  %s%n      %s%n".formatted(change.location(), change.detail())));
            report.append('\n');
        }

        if (breaking.isEmpty()) {
            report.append("PASS - every change is additive, so no new API revision is required.\n");
        } else if (result.revisionDeclared()) {
            report.append("""
                    PASS - the changes above are breaking, but the API revision advanced from \
                    %s to %s, which declares them.
                    Confirm a transformation is registered for %s so pinned merchants keep \
                    receiving the shape they integrated against (D156).
                    """.formatted(result.previousRevision(), result.currentRevision(), result.previousRevision()));
        } else {
            report.append("""
                    FAIL - the public contract was broken without declaring a new API revision.

                    /v1 is a promise (invariant 1). Breaking it is allowed; breaking it \
                    silently is not, because every SDK in M22 is generated from this document \
                    and every merchant pinned to %s still expects the shape above.

                    Either:
                      - make the change additive, or
                      - cut a new dated revision in ApiVersions, register a transformation \
                    from it back to %s, and regenerate the baseline with `gradlew mergeOpenApi`.
                    """.formatted(result.previousRevision(), result.previousRevision()));
        }
        return report.toString();
    }
}
