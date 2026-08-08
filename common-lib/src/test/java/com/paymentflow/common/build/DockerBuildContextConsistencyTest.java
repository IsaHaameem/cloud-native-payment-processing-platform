package com.paymentflow.common.build;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the shared {@code Dockerfile}'s build context in step with {@code settings.gradle.kts}.
 *
 * <p>Gradle configures <em>every</em> project named in {@code settings.gradle.kts} on every
 * invocation, whichever single module is actually being built. So the one-parameterized-Dockerfile
 * design (M9) has an obligation that is easy to miss and invisible until CI runs: each included
 * module's {@code build.gradle.kts} must be copied into the builder stage, even for modules whose
 * source no image will ever need. Miss one and the image build fails for <em>all nine</em>
 * services, during Gradle's configuration phase, with a message naming the module that was
 * forgotten rather than the module being built — which reads as a mysterious platform-wide
 * breakage rather than as the one-line omission it is.
 *
 * <p>This has now happened three times, once per module added to the build that no service
 * consumes at runtime: {@code load-tests} (M14), {@code openapi-tools} (M21.3), and
 * {@code test-support} (M21.7). Two of the three were discovered by a red CI. The repository's
 * rule for a pattern reaching its third occurrence is to stop remembering it, and its rule for
 * an invariant is to make the invalid state unrepresentable rather than to document it — so the
 * obligation is asserted here instead of being restated in one more comment.
 *
 * <p>Both directions matter. A module included but not copied breaks every image build. A copy
 * line for a module no longer in the build is dead weight that fails the image build too, since
 * {@code COPY} of a path absent from the context is an error, not a no-op.
 */
class DockerBuildContextConsistencyTest {

    private static final Path SETTINGS = Path.of("..", "settings.gradle.kts");
    private static final Path DOCKERFILE = Path.of("..", "Dockerfile");
    private static final Path DOCKERIGNORE = Path.of("..", ".dockerignore");

    /** {@code include("payment-service")}, with or without Gradle's optional leading colon. */
    private static final Pattern INCLUDE = Pattern.compile("^\\s*include\\(\"[:]?([^\"]+)\"\\)", Pattern.MULTILINE);

    /**
     * {@code COPY payment-service/build.gradle.kts payment-service/build.gradle.kts}, and
     * since M22.1 {@code COPY sdks/shared/build.gradle.kts …} — the character class admits
     * {@code /} because a Gradle path may be nested. Without it {@code :sdks:shared} could
     * never match any COPY line and the first test below would fail on a Dockerfile that is
     * in fact correct, which is the worse of the two ways to get this wrong: a guard nobody
     * can satisfy gets deleted.
     */
    private static final Pattern COPY_BUILD_FILE =
            Pattern.compile("^COPY\\s+([\\w./-]+)/build\\.gradle\\.kts", Pattern.MULTILINE);

    /**
     * Both files are read in terms of the module's <em>directory</em>. Gradle writes a nested
     * project as {@code sdks:shared} and every path in the Dockerfile and {@code .dockerignore}
     * spells the same thing {@code sdks/shared}, so the two sets are only comparable once one
     * spelling is chosen — and the directory is the one both artefacts under test actually use.
     */
    private static Set<String> moduleDirectories(Pattern pattern, Path file) throws IOException {
        Matcher matcher = pattern.matcher(Files.readString(file, StandardCharsets.UTF_8));
        Set<String> found = new LinkedHashSet<>();
        while (matcher.find()) {
            found.add(matcher.group(1).replace(':', '/'));
        }
        return found;
    }

    @Test
    void everyIncludedModulesBuildFileIsCopiedIntoTheBuilderStage() throws IOException {
        Set<String> included = moduleDirectories(INCLUDE, SETTINGS);
        Set<String> copied = moduleDirectories(COPY_BUILD_FILE, DOCKERFILE);

        // Guard the guard: a regex that silently matched nothing would make this test pass
        // for the wrong reason, which is precisely the failure mode M21.6 recorded.
        assertThat(included).describedAs("no include(...) parsed from settings.gradle.kts").isNotEmpty();
        assertThat(copied).describedAs("no build-file COPY parsed from the Dockerfile").isNotEmpty();

        assertThat(copied)
                .describedAs("every module in settings.gradle.kts needs a `COPY <module>/build.gradle.kts` "
                        + "line in the Dockerfile — Gradle configures all of them on every invocation, so a "
                        + "missing one fails the image build for all nine services")
                .containsAll(included);
    }

    @Test
    void everyCopiedBuildFileBelongsToAModuleStillInTheBuild() throws IOException {
        Set<String> included = moduleDirectories(INCLUDE, SETTINGS);
        Set<String> copied = moduleDirectories(COPY_BUILD_FILE, DOCKERFILE);

        assertThat(included)
                .describedAs("the Dockerfile copies a build file for a module no longer in "
                        + "settings.gradle.kts — COPY of a path missing from the context is an error, so "
                        + "this fails every image build rather than being ignored")
                .containsAll(copied);
    }

    @Test
    void aModuleExcludedFromTheContextStillReadmitsItsBuildFile() throws IOException {
        List<String> ignore = Files.readAllLines(DOCKERIGNORE, StandardCharsets.UTF_8);

        // The other half of the same invariant: .dockerignore drops the source of modules no
        // image needs (load-tests, openapi-tools, test-support, sdks), and each such exclusion
        // has to re-admit the one file the Dockerfile then copies. Excluding without re-admitting
        // fails the build exactly as forgetting the COPY line does.
        for (String module : moduleDirectories(INCLUDE, SETTINGS)) {
            if (excludedByItselfOrAnAncestor(ignore, module)) {
                assertThat(ignore)
                        .describedAs("%s is excluded from the Docker build context but its build file is "
                                + "not re-admitted, so the Dockerfile cannot copy it", module)
                        .contains("!" + module + "/build.gradle.kts");
            }
        }
    }

    /**
     * Whether {@code .dockerignore} drops this module's directory, directly or by dropping
     * something it lives under.
     *
     * <p>The ancestor half is what M22.1 needed: {@code sdks/} excludes {@code sdks/shared}
     * without ever naming it. Checking only for the module's own line would have found no
     * exclusion, asserted nothing, and let a missing {@code !sdks/shared/build.gradle.kts}
     * through — a guard that passes by not looking, which is the exact failure mode the
     * repository's testing rules single out.
     */
    private static boolean excludedByItselfOrAnAncestor(List<String> ignore, String module) {
        for (String prefix = module; prefix != null; prefix = parentOf(prefix)) {
            if (ignore.contains(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String parentOf(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 ? null : path.substring(0, lastSlash);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // M23.1 — the other way a directory reaches the build context.
    // ─────────────────────────────────────────────────────────────────────────────────────

    private static final Path REPOSITORY_ROOT = Path.of("..");

    /**
     * Every non-JVM toolchain tree is excluded from the Docker build context.
     *
     * <p>The three assertions above are about <em>Gradle modules</em>, because until M23.1 that
     * was the only way a directory could matter to an image build. The developer portal is the
     * first top-level directory that is not a Gradle module and not consumed by any service —
     * so {@code settings.gradle.kts} never mentions it, the Dockerfile never copies it, and
     * none of the checks above would notice it either way.
     *
     * <p>What it would do is ride along in the context. Docker sends the whole context to the
     * daemon before the first {@code COPY} runs, the image matrix builds nine services, and a
     * Node application's tree is not small. {@code node_modules/} and {@code .next/} are already
     * excluded globally, which is exactly what makes this easy to get wrong: the expensive part
     * is already handled, so the source tree looks harmless and gets left in.
     *
     * <p>The set is <b>discovered, not listed</b>. Anything with a {@code package.json} under it
     * is a Node toolchain tree and has to be excluded; adding a second portal, or a docs site,
     * fails here until {@code .dockerignore} learns about it, without anyone remembering to
     * extend a constant. That is the same reason the assertions above parse their inputs rather
     * than hard-coding the module list.
     */
    @Test
    void everyNodeToolchainDirectoryIsExcludedFromTheBuildContext() throws IOException {
        List<String> ignore = Files.readAllLines(DOCKERIGNORE, StandardCharsets.UTF_8);
        Set<String> nodeTrees = topLevelNodeToolchainDirectories();

        // Guard the guard: a discovery that found nothing would make this pass by not looking,
        // which is the failure mode the repository's testing rules single out.
        assertThat(nodeTrees)
                .describedAs("no top-level directory with a package.json was found — the discovery "
                        + "below is broken, or this test is running from an unexpected directory")
                .isNotEmpty();

        assertThat(ignore)
                .describedAs("every Node toolchain tree must be excluded from the Docker build "
                        + "context; the whole context is sent to the daemon once per image, and the "
                        + "matrix builds nine")
                .containsAll(nodeTrees.stream().map(directory -> directory + "/").toList());
    }

    /**
     * Top-level directories containing a {@code package.json} within two levels.
     *
     * <p>Two levels because that covers both shapes the repository actually has — the portal
     * keeps its manifest at the root of its own directory, and {@code sdks} keeps its one level
     * down in {@code sdks/node}. Deeper would start walking {@code node_modules}, where every
     * dependency has a manifest and none of them says anything about this repository.
     */
    private static Set<String> topLevelNodeToolchainDirectories() throws IOException {
        Set<String> found = new LinkedHashSet<>();
        try (var top = Files.list(REPOSITORY_ROOT)) {
            for (Path candidate : top.filter(Files::isDirectory).toList()) {
                String name = candidate.getFileName().toString();
                if (name.startsWith(".") || "node_modules".equals(name)) {
                    continue;
                }
                if (containsManifestWithinTwoLevels(candidate)) {
                    found.add(name);
                }
            }
        }
        return found;
    }

    private static boolean containsManifestWithinTwoLevels(Path directory) throws IOException {
        if (Files.exists(directory.resolve("package.json"))) {
            return true;
        }
        try (var children = Files.list(directory)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                if (!"node_modules".equals(child.getFileName().toString())
                        && Files.exists(child.resolve("package.json"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
