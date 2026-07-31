package com.paymentflow.sdk.codegen;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The two Gradle entry points: regenerate the SDK sources, or fail because they are stale
 * (M22.1).
 *
 * <p>One class with two modes rather than two, because the only honest way to answer "are the
 * committed files what the spec produces" is to produce them and compare. Splitting the modes
 * would let the checker's idea of the output drift from the generator's — which is the same
 * failure the thing it is checking for.
 *
 * <p><b>Line endings are normalised before comparing.</b> This repository has been bitten
 * before by a PowerShell rewrite turning a committed file CRLF (§18 warning 5), and a
 * freshness gate that reported every generated file as stale on a Windows checkout would be
 * turned off within a day.
 */
public final class SdkCodegenCli {

    private SdkCodegenCli() {
    }

    public static void main(String[] args) throws IOException {
        Path spec = null;
        Path root = null;
        boolean check = false;
        boolean write = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--spec" -> spec = Path.of(args[++i]);
                case "--root" -> root = Path.of(args[++i]);
                case "--check" -> check = true;
                case "--write" -> write = true;
                default -> fail("unknown argument `" + args[i] + "`");
            }
        }
        if (spec == null || root == null || check == write) {
            fail("usage: --spec <openapi.yaml> --root <repository root> (--write | --check)");
        }

        SdkCodegen.Result result = SdkCodegen.generate(Files.readString(spec, StandardCharsets.UTF_8));

        // Reported before anything is written or compared. A construct the reader could not
        // describe means the emitted types are wrong now, and finding that out after a
        // successful-looking regeneration is finding out too late.
        if (!result.unsupported().isEmpty()) {
            System.err.println("The SDK generator has no rule for part of docs/openapi.yaml:");
            result.unsupported().forEach(finding -> System.err.println("  - " + finding));
            System.err.println();
            System.err.println("Every rule is deliberate and the fallback is deliberately a failure: "
                    + "a generator that quietly emits `unknown` for a field the contract describes "
                    + "precisely is worse than one that stops. Add the rule to SdkSpecReader.");
            System.exit(1);
        }

        if (write) {
            write(root, result);
        } else {
            check(root, result);
        }
    }

    // ── Write ───────────────────────────────────────────────────────────────────────────

    private static void write(Path root, SdkCodegen.Result result) throws IOException {
        for (String directory : SdkCodegen.generatedDirectories()) {
            deleteContents(root.resolve(directory));
        }
        for (Map.Entry<String, String> file : result.files().entrySet()) {
            Path target = root.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
        }
        System.out.printf("generated %d SDK files into %s%n",
                result.files().size(), String.join(", ", SdkCodegen.generatedDirectories()));
    }

    // ── Check ───────────────────────────────────────────────────────────────────────────

    private static void check(Path root, SdkCodegen.Result result) throws IOException {
        List<String> problems = new ArrayList<>();

        for (Map.Entry<String, String> file : result.files().entrySet()) {
            Path target = root.resolve(file.getKey());
            if (!Files.exists(target)) {
                problems.add(file.getKey() + " is missing");
                continue;
            }
            String committed = normalise(Files.readString(target, StandardCharsets.UTF_8));
            if (!committed.equals(normalise(file.getValue()))) {
                problems.add(file.getKey() + " no longer matches what docs/openapi.yaml generates");
            }
        }

        // The other direction. A model deleted from the contract leaves its generated file
        // behind, and an SDK still exporting a type the API no longer has is a worse lie than
        // one missing a type it does.
        Set<String> expected = new LinkedHashSet<>(result.files().keySet());
        for (String directory : SdkCodegen.generatedDirectories()) {
            for (String found : filesUnder(root, directory)) {
                if (!expected.contains(found)) {
                    problems.add(found + " is not generated from docs/openapi.yaml any more - delete it");
                }
            }
        }

        if (problems.isEmpty()) {
            System.out.printf("SDK sources are up to date with docs/openapi.yaml (%d files)%n",
                    result.files().size());
            return;
        }

        System.err.println("The committed SDK sources are stale:");
        problems.stream().sorted().forEach(problem -> System.err.println("  - " + problem));
        System.err.println();
        System.err.println("Run `./gradlew :sdks:shared:generateSdkSources` and commit the result.");
        System.exit(1);
    }

    // ── Filesystem helpers ──────────────────────────────────────────────────────────────

    private static List<String> filesUnder(Path root, String directory) throws IOException {
        Path base = root.resolve(directory);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(Files::isRegularFile)
                    // Repository-relative and forward-slashed, so the comparison against the
                    // generator's own keys is not a function of which OS is running it.
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .filter(path -> !isToolOutput(path))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Whether a path under a generated directory is something a toolchain left there rather
     * than something this generator wrote.
     *
     * <p>Python drops {@code __pycache__} beside every module it imports, so running the SDK's
     * own test suite is enough to fill the generated package with bytecode — and without this,
     * the orphan check reports four "stale" files immediately after a successful regeneration.
     * A freshness gate that fails because the tests ran is a freshness gate that gets ignored.
     * Matched on the path segment rather than the extension, so a {@code .pyc} the generator
     * did somehow emit would still be reported.
     */
    private static boolean isToolOutput(String path) {
        return path.contains("/__pycache__/");
    }

    private static void deleteContents(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(directory))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }

    /** Trailing whitespace is left alone; only the line ending is normalised. */
    private static String normalise(String content) {
        return content.replace("\r\n", "\n");
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
