/*
 * notification-service — simulated email logging + real webhook delivery on payment
 * lifecycle events, with an explicit Kafka retry topic and dead-letter topic for
 * webhook delivery failures (D10's "declared explicitly, no auto-create" policy,
 * extended to a producer of its own topics for the first time in this platform).
 *
 * Outbound webhook delivery uses Spring Web's RestClient rather than OpenFeign —
 * OpenFeign is for typed internal clients to known services (D32's MerchantClient); this
 * is an HTTP POST to an arbitrary, merchant-configured external URL, which RestClient
 * models directly.
 *
 * M18.2 ends this service's "Kafka is my only inbound interface" era (D42's scope
 * discipline, correct while no caller existed): the webhook-endpoint management API
 * (§4.5) is a real, key-authenticated public surface, so Spring Security arrives — but
 * *only* for common-lib's InternalContextFilter (D100), exactly as sandbox-service does
 * (M17.2). There is deliberately no spring-boot-starter-oauth2-resource-server: this
 * service never accepts a JWT, because the /api/v1 dashboard mirror is deferred to M23
 * (D133).
 */
plugins {
    id("paymentflow.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    // M18.2: solely to host common-lib's InternalContextFilter (D100) on the webhook
    // management API — no OAuth2 resource server, since this service never sees a JWT
    // (D133). Mirrors sandbox-service's identical, deliberately minimal security setup.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // M21.2: the OpenAPI 3.1 description of this service's public /v1 surface. The -api
    // starter and NOT -ui, for the reason M21.1 recorded on payment-service: the document
    // is an artefact other things consume (M21.3's merge task, M22's SDK generators,
    // M25's docs site), and the interactive console is the portal's job (M23/M24),
    // rendered against the merged spec rather than six per-service Swagger pages behind a
    // gateway that routes none of them.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    // Concrete Micrometer registry backend + distributed tracing (M13).
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")

    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation(libs.awaitility)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
