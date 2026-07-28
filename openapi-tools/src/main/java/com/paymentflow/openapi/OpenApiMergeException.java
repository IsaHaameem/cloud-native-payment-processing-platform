package com.paymentflow.openapi;

import java.util.List;

/**
 * Thrown when the six fragments cannot be merged into one document (M21.3).
 *
 * <p>Carries every conflict found rather than only the first. A contract drift rarely
 * shows up in one place — a renamed shared schema breaks every {@code $ref} to it at once —
 * and a merge step that reports one problem per build is one developers learn to route
 * around rather than read.
 */
public class OpenApiMergeException extends RuntimeException {

    private final transient List<String> conflicts;

    public OpenApiMergeException(List<String> conflicts) {
        super(format(conflicts));
        this.conflicts = List.copyOf(conflicts);
    }

    /** The individual conflicts, for tests that assert on them rather than on the prose. */
    public List<String> conflicts() {
        return conflicts;
    }

    private static String format(List<String> conflicts) {
        // ASCII only in anything printed to a console. Everything here reaches a terminal
        // or a CI log, and on a Windows console (cp1252) an em-dash renders as a
        // replacement character — observed while proving this gate fails.
        StringBuilder message = new StringBuilder(
                "The per-service OpenAPI fragments cannot be merged - they disagree about the API they all describe.\n");
        conflicts.forEach(conflict -> message.append("\n  - ").append(conflict.replace("\n", "\n    ")));
        message.append("""

                Each of these is a contract bug rather than a merge-tool limitation: the merged
                document has exactly one info block, one definition per component name, and one
                owner per path. Fix the fragments, not this message.""");
        return message.toString();
    }
}
