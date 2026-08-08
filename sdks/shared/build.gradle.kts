/*
 * sdks/shared — the one code generator that feeds both SDKs (M22.1).
 *
 * It reads the published `docs/openapi.yaml` and writes three things: TypeScript models
 * into sdks/node, Python models into sdks/python, and language-neutral golden fixtures both
 * SDKs' tests assert against. Nothing here ships to npm or PyPI; the generated files do.
 *
 * ── Why the generator is Java, in a Gradle module, in a monorepo whose SDKs are not ──
 *
 * D136 settled the shape of this question already, for the webhook signature vectors: making
 * `node` and `python` build prerequisites of a JVM monorepo means a contributor with neither
 * cannot build at all. The same reasoning applies with more force here, because this is not a
 * one-off script — it is a gate. The thing that stops a checked-in model drifting from the
 * spec has to run in the build that owns the spec, and `./gradlew build` is that build.
 * A generator written in TypeScript could only be verified by a CI job that a contributor
 * without Node never runs, which is how a stale generated file reaches `main`.
 *
 * It also reuses rather than reimplements. `:openapi-tools` already parses this document,
 * merges it, and diffs it, with 65 tests behind it; a second OpenAPI reader written in
 * TypeScript would be the third implementation of a walk over the same tree (§15's
 * no-duplicated-code rule), and the first one with no tests.
 *
 * ── Why the SDKs themselves are not Gradle projects ──
 *
 * They are npm and PyPI packages. Their toolchains build, type-check and test them, and CI
 * runs those in jobs of their own (see .github/workflows/ci.yml). Wrapping `npm` in a Gradle
 * task would put the prerequisite back that the paragraph above exists to keep out.
 */
plugins {
    id("paymentflow.java-conventions")
}

dependencies {
    api(platform(project(":platform-bom")))

    // The document reader, the merge, and the diff all live here already. This module adds a
    // third consumer of the same parsed tree rather than a second parser.
    implementation(project(":openapi-tools"))

    // Jackson 2, matching :openapi-tools. Same rationale as that module's build file: this is
    // tooling that reads and rewrites documents, no service depends on it at runtime, and the
    // two Jackson lines never meet.
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation(platform(project(":platform-bom")))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ── The code generation pipeline (M22.1) ────────────────────────────────────────────────
//
// Two tasks over one implementation, in the same shape M21.3 established for the OpenAPI
// baseline and for the same reason: the task that *writes* the artefact and the task that
// *judges* it must not be the same task. A verification step that repaired what it verifies
// could never fail twice, and the second run would report success on a change nobody read.

/** The published contract. The generator's only input. */
val openApiBaseline: File = rootProject.layout.projectDirectory.file("docs/openapi.yaml").asFile

/** The repository root: the generator writes into sdks/node and sdks/python beneath it. */
val repositoryRoot: File = rootProject.layout.projectDirectory.asFile

/**
 * Everything the generator owns, declared so Gradle's up-to-date checking is about the files
 * that actually matter rather than about the whole SDK tree. Listed here rather than derived
 * from a glob because an output that appears only when the generator runs cannot be globbed
 * before it does.
 */
val generatedRoots = listOf(
    rootProject.layout.projectDirectory.dir("sdks/node/src/generated").asFile,
    // M23.1 (D189): the developer portal's copy of the same TypeScript. Listed here, not just
    // emitted, because these declarations are what make verifySdkSources fail on a stale or
    // hand-edited portal model — without it Gradle would report the task UP-TO-DATE after the
    // one edit the gate exists to catch.
    rootProject.layout.projectDirectory.dir("developer-portal/src/generated").asFile,
    rootProject.layout.projectDirectory.dir("sdks/python/src/paymentflow/_generated").asFile,
    rootProject.layout.projectDirectory.dir("sdks/shared/fixtures").asFile,
)

tasks.register<JavaExec>("generateSdkSources") {
    group = "documentation"
    description = "Regenerates the Node, portal and Python models and the shared fixtures from docs/openapi.yaml (M22.1, M23.1)."

    inputs.file(openApiBaseline)
        .withPropertyName("openApiBaseline")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .normalizeLineEndings()
    outputs.dirs(generatedRoots)

    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.paymentflow.sdk.codegen.SdkCodegenCli"
    args = listOf("--spec", openApiBaseline.absolutePath, "--root", repositoryRoot.absolutePath, "--write")
}

tasks.register<JavaExec>("verifySdkSources") {
    group = "verification"
    description = "Fails if the committed generated sources no longer match what docs/openapi.yaml generates (M22.1, M23.1)."

    // Both the spec and the generated files are inputs: this task's answer changes when
    // either side moves, and a task that declared only the spec would report UP-TO-DATE
    // after someone hand-edited a generated file — the one edit it exists to catch.
    inputs.file(openApiBaseline)
        .withPropertyName("openApiBaseline")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .normalizeLineEndings()
    inputs.files(generatedRoots)
        .withPropertyName("generatedSdkSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .normalizeLineEndings()

    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.paymentflow.sdk.codegen.SdkCodegenCli"
    args = listOf("--spec", openApiBaseline.absolutePath, "--root", repositoryRoot.absolutePath, "--check")
}

// SdkCodegenTest reads the real docs/openapi.yaml, which lives outside this module's source
// set — so Gradle would infer nothing from it and report `test` UP-TO-DATE after the contract
// changed, silently not running the two assertions that are about the contract as it actually
// is. §18 warning 6 records this trap; common-lib's build file carries the same declaration
// for the same reason.
tasks.named<Test>("test") {
    inputs.file(openApiBaseline)
        .withPropertyName("openApiBaseline")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .normalizeLineEndings()
}

// The freshness gate runs in `check`, so `./gradlew build` fails on a stale generated model
// rather than leaving it for a CI job that only the SDK toolchains reach. M22's own risk
// table names this: "a stale checked-in type fails the build".
tasks.named("check") {
    dependsOn("verifySdkSources")
}
