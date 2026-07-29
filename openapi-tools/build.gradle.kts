/*
 * openapi-tools — the merge step that turns the six per-service OpenAPI fragments into
 * the one `docs/openapi.yaml` this platform publishes (M21.3, §5/M21 task 2).
 *
 * Not a service and not on any service's runtime classpath. It is build tooling, in the
 * same sense `load-tests` is: a module that exists to be run by the build rather than
 * deployed. It lives here rather than in `build-logic` because the merge has real logic —
 * deduplicating shared components, and refusing to merge fragments that disagree — and
 * this platform's convention is that logic like that gets ordinary unit tests rather than
 * being exercised only through a Gradle invocation.
 *
 * The one exception to "not on a service's classpath" is `OpenApiFragments`, which the six
 * services' document tests use to write their fragment out; that is a `testImplementation`
 * dependency in each of them, and nothing links this module into a running service.
 */
plugins {
    id("paymentflow.java-conventions")
}

dependencies {
    // `api`, not `implementation`, and for the same reason every other module does it: the
    // BOM carries the *versions*, so a consumer resolving this module's runtime classpath
    // (the root project's `mergeOpenApi`) needs it exported. With `implementation` the
    // versionless Jackson coordinates below resolve to nothing outside this module.
    api(platform(project(":platform-bom")))

    // Jackson 2, not the Jackson 3 the services serialize with. Deliberate: this module
    // reads and rewrites documents rather than binding them to types, `jackson-dataformat-
    // yaml` is the mature renderer on the 2.x line, and Boot 4 manages both lines precisely
    // so tooling like this can sit on 2.x while applications move to 3.x (M21.1 established
    // the same split for swagger-core). No service depends on this module at runtime, so
    // the two lines never meet.
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation(platform(project(":platform-bom")))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ── The published OpenAPI document (M21.3, §5/M21 task 2) ───────────────────────────────
//
// Six services each describe the slice of the public `/v1` tier they serve;
// `docs/openapi.yaml` is the one document the platform publishes, and the artefact M22's
// SDK generators, M25's documentation site, and M21.6's breaking-change gate all read.
//
// The tasks live here rather than in the root build file for two reasons. The root file
// states outright that it is intentionally thin, and — more practically — the classpath a
// `JavaExec` needs is this module's own `runtimeClasspath`, which exists here and has to be
// reconstructed through a hand-attributed detached configuration anywhere else. The first
// attempt did exactly that and failed on variant selection, with an error that reads as a
// missing Jackson dependency rather than a missing variant.

/** The services that publish a public `/v1` tier, in documentation order (§17/M21). */
val openApiServices = listOf(
    "payment-service",
    "transaction-service",
    "audit-service",
    "analytics-service",
    "notification-service",
    "sandbox-service",
)

// Resolved to plain paths at configuration time. Deliberately not a
// `CommandLineArgumentProvider`: a lambda written in a build script captures the script
// object itself, which the configuration cache cannot serialize, and none of these values
// need deferring — they are directory layout, known before anything runs.
val openApiFragmentFiles: List<File> = openApiServices.map { service ->
    rootProject.project(":$service").layout.buildDirectory.file("openapi/$service.json").get().asFile
}

/** The committed baseline. Under `docs/` because it is published, not build output. */
val openApiBaseline: File = rootProject.layout.projectDirectory.file("docs/openapi.yaml").asFile
val openApiMergedCopy: File = layout.buildDirectory.file("openapi/openapi.yaml").get().asFile

val fragmentTasks = openApiServices.map { ":$it:openApiFragment" }

tasks.register<JavaExec>("mergeOpenApi") {
    group = "documentation"
    description = "Regenerates docs/openapi.yaml from the six services' OpenAPI fragments (M21.3)."

    dependsOn(fragmentTasks)
    inputs.files(openApiFragmentFiles)
    outputs.file(openApiBaseline)

    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.paymentflow.openapi.OpenApiMergeCli"
    args = listOf("--out", openApiBaseline.absolutePath) + openApiFragmentFiles.map { it.absolutePath }
}

tasks.register<JavaExec>("verifyOpenApiBaseline") {
    group = "verification"
    description = "Fails if docs/openapi.yaml no longer matches what the services serve (M21.3)."

    dependsOn(fragmentTasks)
    inputs.files(openApiFragmentFiles)
    inputs.file(openApiBaseline)

    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.paymentflow.openapi.OpenApiMergeCli"
    // Writes to the build directory rather than over the committed file: a verification
    // task that repaired what it verifies could never fail twice, and the second run would
    // report success on a change nobody reviewed.
    args = listOf("--out", openApiMergedCopy.absolutePath, "--baseline", openApiBaseline.absolutePath) +
        openApiFragmentFiles.map { it.absolutePath }
}

// ── The breaking-change gate (M21.6, §5/M21 task 5) ─────────────────────────────────────
//
// Two different questions, deliberately two tasks:
//
//   verifyOpenApiBaseline       is docs/openapi.yaml still what the code serves?
//   verifyOpenApiCompatibility  is docs/openapi.yaml still compatible with the one before it?
//
// The first needs six Spring contexts and six Postgres containers, because the only place
// the document exists is a running application. The second is a comparison of two files and
// runs in under a second. Fusing them would make the cheap, most-often-failing check pay the
// expensive one's price on every invocation, and would leave no way to ask "is this change
// breaking?" about a document you already have.

/** The document being compared against — CI writes the base branch's copy here. */
val openApiPreviousBaseline: String = providers.gradleProperty("openApiPreviousBaseline")
    .getOrElse(layout.buildDirectory.file("openapi/previous-openapi.yaml").get().asFile.absolutePath)

// Overridable too, so the task is usable as a general "are these two documents compatible?"
// question and not only as "is HEAD compatible with the base branch". That is the form the
// gate gets used in while a revision is being cut, when the interesting comparison is
// against a document that is not committed anywhere yet.
val openApiCurrentBaseline: String = providers.gradleProperty("openApiCurrentBaseline")
    .getOrElse(openApiBaseline.absolutePath)

val openApiDiffReport: File = layout.buildDirectory.file("openapi/contract-diff.txt").get().asFile

tasks.register<JavaExec>("verifyOpenApiCompatibility") {
    group = "verification"
    description = "Fails if docs/openapi.yaml breaks the previous contract without declaring a new API revision (M21.6)."

    // Deliberately no `dependsOn(fragmentTasks)`. This task judges the *committed* baseline,
    // which is the artefact M22's SDKs are generated from; whether that file still matches
    // the code is verifyOpenApiBaseline's question and is asked separately. Coupling them
    // here would mean a developer could not ask "did I break the contract?" without first
    // standing up six services.
    inputs.file(openApiCurrentBaseline)

    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.paymentflow.openapi.OpenApiDiffCli"
    args = listOf(
        "--previous", openApiPreviousBaseline,
        "--current", openApiCurrentBaseline,
        "--summary", openApiDiffReport.absolutePath,
    )
}
