/*
 * Applied by the six services that publish a public `/v1` tier (M21.3).
 *
 * Each of them already has an `OpenApiDocumentIntegrationTest` that stands the service up
 * on Testcontainers, fetches `/v3/api-docs`, and asserts the document is correct. This
 * plugin adds one focused `Test` task that runs *only* that class and keeps the document it
 * fetched, so the merge step has a fragment to work with.
 *
 * Why a convention plugin rather than ten lines in six build files: the wiring is identical
 * in all six — same task, same filter, same output directory — and §5.0 standing rule 4 is
 * that a pattern appearing a third time moves somewhere shared. The alternative also has a
 * failure mode worth avoiding: a seventh service added later would have to remember to copy
 * it, and a missing fragment does not fail the merge loudly, it just silently publishes a
 * smaller API.
 *
 * Why not simply depend on `test`: `mergeOpenApi` would then pull six full integration
 * suites (~9 minutes) to produce six JSON files. Filtering to the one class that already
 * has to pass keeps the fragment honest — it is a by-product of an assertion, not a second
 * uncontrolled way of generating the contract — without making "regenerate the spec" a
 * coffee break.
 */
plugins {
    id("paymentflow.java-conventions")
}

val openApiFragmentDir: Provider<Directory> = layout.buildDirectory.dir("openapi")

dependencies {
    // OpenApiFragments — the writer the document test calls. Test scope only: nothing links
    // openapi-tools into a running service.
    "testImplementation"(project(":openapi-tools"))
}

// Resolved here rather than inside the task's configuration block: inside it, `the<...>()`
// looks the extension up on the *task*, which has only ExtraPropertiesExtension, and fails
// with a message that reads as though the java plugin were missing.
val testSourceSet: SourceSet = the<SourceSetContainer>()["test"]

tasks.register<Test>("openApiFragment") {
    group = "documentation"
    description = "Generates this service's OpenAPI fragment for the merged docs/openapi.yaml (M21.3)."

    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath

    filter {
        // The class is named identically in all six services, which is what lets this be
        // shared. If a service ever renames it, Gradle fails with "no tests found" rather
        // than quietly contributing nothing to the merged document.
        includeTestsMatching("*OpenApiDocumentIntegrationTest")
    }

    systemProperty("paymentflow.openapi.fragmentDir", openApiFragmentDir.get().asFile.absolutePath)
    outputs.dir(openApiFragmentDir)

    // Never both at once: the same Testcontainers Postgres would be started twice for the
    // same class, and on a loaded Docker daemon that is exactly how the pre-M21.3 audit's
    // 18 spurious failures happened (§14).
    mustRunAfter(tasks.named("test"))
}
