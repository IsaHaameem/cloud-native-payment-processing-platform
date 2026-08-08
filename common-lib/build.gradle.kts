/*
 * common-lib — cross-cutting building blocks shared by services: exception hierarchy,
 * error codes, the standard ApiError contract, correlation-id propagation, and the
 * global exception handler, wired as Spring Boot auto-configuration.
 *
 * The servlet/web dependencies are `compileOnly`: this module compiles against them,
 * but does NOT force the servlet stack onto consumers. Servlet services already bring
 * spring-boot-starter-web; the reactive gateway does not, and the SERVLET-conditional
 * auto-config simply stays inactive there.
 */
plugins {
    id("paymentflow.java-conventions")
}

dependencies {
    api(platform(project(":platform-bom")))
    api(project(":common-dto"))
    api("org.slf4j:slf4j-api")

    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    compileOnly("org.springframework.boot:spring-boot-starter-validation")
    // M15: InternalContextFilter / MerchantContextAuthenticationToken (internal-context
    // header verification, D100/D118). compileOnly for the same reason as the web/
    // validation deps above — every servlet service already brings
    // spring-boot-starter-security itself; common-lib only needs the types to compile
    // against, not to force security onto a module that doesn't want it (the gateway
    // brings its own reactive security starter instead, and this filter is
    // SERVLET-conditional so it never activates there anyway).
    compileOnly("org.springframework.boot:spring-boot-starter-security")
    // M13: ObservabilityAutoConfiguration's MeterRegistryCustomizer bean. compileOnly
    // for the same reason as the web/validation deps above — every service already
    // brings spring-boot-starter-actuator (+ a concrete Micrometer registry) itself;
    // common-lib only needs these types to compile against, not to force as a
    // transitive dependency onto a module that doesn't want actuator at all.
    compileOnly("io.micrometer:micrometer-core")
    compileOnly("org.springframework.boot:spring-boot-micrometer-metrics")
    // M20.1: RequestRedactor walks a JSON body structurally (field-name redaction) rather
    // than only regex-scrubbing its text. compileOnly for the same reason as everything
    // above — every consumer already has Jackson on the classpath via a Boot starter.
    compileOnly("tools.jackson.core:jackson-databind")
    // M20.7: ResilienceMetricsAutoConfiguration binds Resilience4j's Micrometer meters
    // explicitly, closing V1 known issue #9 (see that class for the Boot 4 relocation that
    // caused it). compileOnly like everything above — only the two services that actually use
    // Resilience4j bring it, and @ConditionalOnClass keeps the auto-config inert everywhere else.
    compileOnly("io.github.resilience4j:resilience4j-micrometer")
    compileOnly("io.github.resilience4j:resilience4j-circuitbreaker")
    compileOnly("io.github.resilience4j:resilience4j-retry")
    compileOnly("io.github.resilience4j:resilience4j-bulkhead")
    compileOnly("io.github.resilience4j:resilience4j-timelimiter")
    compileOnly("io.github.resilience4j:resilience4j-ratelimiter")
    // M21.2: PublicApiDocument builds the document-level half of every service's OpenAPI
    // fragment (info, server, the SecretKey scheme) so the six fragments M21.3 merges
    // cannot disagree. compileOnly for the same reason as everything above — only the
    // services that publish a /v1 tier bring springdoc, and this class is only referenced
    // from their own OpenApiConfig, so nothing loads it anywhere else.
    //
    // The starter rather than `swagger-models-jakarta` directly: swagger's version is not
    // managed by the Spring Boot BOM and springdoc's own BOM is deliberately not imported
    // (D147), so the starter — which platform-bom does constrain — is the only spelling
    // with a single version source. It arrives transitively at 2.2.41.
    compileOnly(libs.springdoc.starter.webmvc.api)

    // Generates auto-configuration metadata so condition evaluation is fast/lazy.
    // The annotationProcessor configuration doesn't extend implementation, so it needs
    // the BOM applied explicitly to resolve the managed version.
    annotationProcessor(platform(project(":platform-bom")))
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // M20.7: ResilienceMetricsAutoConfigurationTest asserts meters land in a real registry.
    testImplementation("io.github.resilience4j:resilience4j-micrometer")
    testImplementation("io.github.resilience4j:resilience4j-circuitbreaker")
    testImplementation("io.github.resilience4j:resilience4j-retry")
    testImplementation("io.github.resilience4j:resilience4j-bulkhead")
    testImplementation("io.github.resilience4j:resilience4j-timelimiter")
    testImplementation("io.github.resilience4j:resilience4j-ratelimiter")
    // M21.2: PublicApiDocumentTest asserts the shared contract the six fragments agree on.
    testImplementation(libs.springdoc.starter.webmvc.api)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Two of this module's tests assert against files that live outside it —
// ErrorCatalogueDocumentationConsistencyTest reads docs/ERRORS.md, and
// DockerBuildContextConsistencyTest reads the Dockerfile, .dockerignore and
// settings.gradle.kts. Gradle infers a task's inputs from its source set, so without this
// declaration `test` is UP-TO-DATE after a change to any of them and the assertion simply
// does not run: editing the Dockerfile and getting a green local build is exactly the
// sequence that let the missing test-support COPY line reach CI. CI would have caught it
// regardless (`clean build --no-build-cache`), which is the point — a guard that only fires
// in CI is a guard that reports the defect one push later than it could have.
tasks.named<Test>("test") {
    inputs.files(
        rootDir.resolve("Dockerfile"),
        rootDir.resolve(".dockerignore"),
        rootDir.resolve("settings.gradle.kts"),
        rootDir.resolve("docs/ERRORS.md"),
        // M23.1: the manifests that make a directory a Node toolchain tree. The test discovers
        // them by walking the repository root, which Gradle cannot infer — so a new portal or
        // docs site would appear, go unexcluded, and leave `test` reporting UP-TO-DATE. Listing
        // the manifests themselves is the closest declarable thing to "the set of such trees".
        rootDir.resolve("developer-portal/package.json"),
        rootDir.resolve("sdks/node/package.json"),
    )
        .withPropertyName("repositoryFilesAssertedByConsistencyTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        // The repository has been bitten by a PowerShell rewrite turning a file CRLF; a line
        // ending is not a change these tests are asserting anything about.
        .normalizeLineEndings()
}
