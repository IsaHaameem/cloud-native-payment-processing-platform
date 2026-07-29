/*
 * test-support — the scaffold the six public-API services' contract tests extend (M21.7).
 *
 * Why this module exists. Each of the six services had its own `OpenApiDocumentIntegrationTest`
 * with roughly seventy lines of identical scaffold: the cached `/v3/api-docs` fetch, the
 * `tagsOf`/`usedTags` helpers, and five assertions that were the same in intent and nearly
 * the same in text. That breaks §5.0 standing rule 4 — a pattern appearing a third time moves
 * somewhere shared — and it was recorded as known debt rather than fixed at the time, because
 * the monorepo had nowhere to put it. M21.7 adds a *second* round of per-service document
 * assertions on exactly that scaffold, so this is the moment it stops being deferrable: the
 * alternative was copying it a seventh time.
 *
 * Why a module and not `testFixtures` on common-lib. common-lib's web dependencies are
 * `compileOnly` on purpose (D11), so that the reactive gateway is never forced onto the
 * servlet stack by depending on it. A test-fixtures variant carrying spring-boot-webmvc-test
 * and MockMvc would make the module whose entire design is "nobody gets a servlet stack they
 * did not ask for" start exporting one. A separate module keeps that property and follows the
 * shape the repository already uses for code that exists to serve the build rather than to be
 * deployed (openapi-tools, load-tests).
 *
 * Nothing links this into a running service: the six services depend on it in test scope
 * only, wired once by the `paymentflow.openapi-fragment` convention plugin.
 */
plugins {
    id("paymentflow.java-conventions")
}

dependencies {
    // `api`, not `implementation`: consumers resolve this module's classes onto their *test*
    // compile classpath and extend its base classes, so the types those classes expose in
    // their signatures — MockMvc, JsonNode, the assertion library — have to come with them.
    api(platform(project(":platform-bom")))

    // The document these tests assert against, and the fragment writer they call.
    api(project(":openapi-tools"))
    // InternalContextSigner/InternalContextHeaders: every public `/v1` call in a per-service
    // test has to arrive looking as though the gateway signed it.
    api(project(":common-lib"))

    // Jackson 2, matching openapi-tools rather than the services' Jackson 3. The scaffold
    // parses the generated document with the same reader that validates against it, so
    // there is one JsonNode type across the contract tooling and no conversion between the
    // two lines. Declared here rather than inherited because openapi-tools keeps it
    // `implementation` — it is that module's private choice, not part of its API.
    api("com.fasterxml.jackson.core:jackson-databind")

    api("org.springframework.boot:spring-boot-starter-test")
    // @AutoConfigureMockMvc and the MockMvc auto-configuration itself.
    api("org.springframework.boot:spring-boot-webmvc-test")
    // spring-web rather than spring-boot-starter-web: this module needs HttpHeaders and
    // MediaType, not an embedded servlet container. The services bring their own.
    api("org.springframework:spring-web")
    // MockMvc's result types expose HttpServletResponse in their signatures, so the API has
    // to be on the compile classpath even though nothing here uses a servlet directly.
    api("jakarta.servlet:jakarta.servlet-api")

    testImplementation(platform(project(":platform-bom")))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
