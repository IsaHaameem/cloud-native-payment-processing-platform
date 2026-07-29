package com.paymentflow.openapi;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        Path acceptedPath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--previous" -> previousPath = Path.of(args[++i]);
                case "--current" -> currentPath = Path.of(args[++i]);
                case "--summary" -> summaryPath = Path.of(args[++i]);
                case "--accepted" -> acceptedPath = Path.of(args[++i]);
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
        Set<String> accepted = readAccepted(acceptedPath);

        String report = report(result, accepted);
        System.out.print(report);
        if (summaryPath != null) {
            Files.createDirectories(summaryPath.toAbsolutePath().getParent());
            Files.writeString(summaryPath, report, StandardCharsets.UTF_8);
        }

        boolean unaccepted = result.breaking().stream()
                .anyMatch(change -> !accepted.contains(change.location()));
        if (unaccepted && !result.revisionDeclared()) {
            System.exit(1);
        }
    }

    /**
     * Reads the reviewed-and-accepted breaking changes (M21.7).
     *
     * <p><b>Why this exists, and why it is not a general escape hatch.</b> The diff compares
     * two <em>documents</em> and cannot tell "the API changed" from "the description was
     * corrected" — and the second is a real category. M21.7 renamed every operation id from
     * the Java method name springdoc had derived, replaced three invented {@code 200}
     * responses with the {@code 201}/{@code 204} the operations actually return, declared
     * fields nullable that had always been able to be null, and deleted a schema that
     * described a Java class rather than a payload. Not one byte of any request or response
     * changed; every one of those is breaking to a reader of the previous document.
     *
     * <p>Cutting a dated revision for that would be worse than the problem: it would tell
     * every pinned merchant their contract moved when it did not, and would require
     * registering a transformation that transforms nothing (D156). So the acceptance is
     * recorded instead — in a committed file, one location per line, each under a comment
     * saying why, reviewed in the same diff as the change it excuses.
     *
     * <p>Three properties keep it from becoming a rubber stamp: it is committed and shows up
     * in review, every accepted entry is <em>printed on every run</em> rather than silently
     * swallowed, and entries that no longer match anything are reported as no longer
     * applicable so the file is visibly stale rather than quietly permanent.
     */
    private static Set<String> readAccepted(Path accepted) throws IOException {
        if (accepted == null || !Files.exists(accepted)) {
            return Set.of();
        }
        Set<String> locations = new LinkedHashSet<>();
        for (String line : Files.readAllLines(accepted, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                locations.add(trimmed);
            }
        }
        return locations;
    }

    /**
     * One report for both outcomes, written to be read by whoever the gate stopped rather
     * than by whoever wrote it. The additive changes are listed too — a reviewer needs to
     * see that the field they expected to be additive was in fact classified that way, and a
     * gate that only speaks when it is angry teaches people that silence means nothing
     * happened.
     */
    private static String report(OpenApiDiff.Result result, Set<String> accepted) {
        StringBuilder report = new StringBuilder();
        List<OpenApiChange> breaking = result.breaking().stream()
                .filter(change -> !accepted.contains(change.location())).toList();
        List<OpenApiChange> excused = result.breaking().stream()
                .filter(change -> accepted.contains(change.location())).toList();
        List<OpenApiChange> additive = result.additive();

        report.append("OpenAPI contract diff  %s -> %s%n".formatted(
                result.previousRevision(), result.currentRevision()));
        report.append("  %d breaking, %d accepted, %d additive%n%n"
                .formatted(breaking.size(), excused.size(), additive.size()));

        if (result.changes().isEmpty()) {
            report.append("  The published contract is unchanged.\n");
            reportStaleAcceptances(report, result, accepted);
            return report.toString();
        }

        if (!breaking.isEmpty()) {
            report.append("BREAKING\n");
            breaking.forEach(change -> report.append("  %s%n      %s%n".formatted(change.location(), change.detail())));
            report.append('\n');
        }
        if (!excused.isEmpty()) {
            // Printed in full rather than counted. An acceptance nobody re-reads is an
            // acceptance that has stopped meaning anything.
            report.append("ACCEPTED (reviewed, recorded in the acceptance file)\n");
            excused.forEach(change -> report.append("  %s%n      %s%n".formatted(change.location(), change.detail())));
            report.append('\n');
        }
        if (!additive.isEmpty()) {
            report.append("ADDITIVE\n");
            additive.forEach(change -> report.append("  %s%n      %s%n".formatted(change.location(), change.detail())));
            report.append('\n');
        }
        reportStaleAcceptances(report, result, accepted);

        if (breaking.isEmpty() && excused.isEmpty()) {
            report.append("PASS - every change is additive, so no new API revision is required.\n");
        } else if (breaking.isEmpty()) {
            report.append("""
                    PASS - every breaking change above was reviewed and recorded in the \
                    acceptance file. Nothing here changes what a request or a response \
                    contains; each entry corrects what the document *said* about behaviour \
                    that did not move.
                    """);
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

    /**
     * Names acceptances that no longer match anything.
     *
     * <p>Reported rather than failed, deliberately. The commit that lands a correction needs
     * its acceptances; the very next comparison is against the corrected baseline, where
     * every one of them is trivially unmatched — so failing on staleness would make the file
     * impossible to land. Printing them on every run is what stops it from becoming
     * permanent by inattention instead.
     */
    private static void reportStaleAcceptances(StringBuilder report, OpenApiDiff.Result result,
                                               Set<String> accepted) {
        List<String> matched = result.breaking().stream().map(OpenApiChange::location).toList();
        List<String> stale = accepted.stream().filter(location -> !matched.contains(location)).toList();
        if (stale.isEmpty()) {
            return;
        }
        report.append("NO LONGER APPLICABLE - %d acceptance(s) match nothing in this diff and should be deleted:%n"
                .formatted(stale.size()));
        stale.forEach(location -> report.append("  %s%n".formatted(location)));
        report.append('\n');
    }
}
