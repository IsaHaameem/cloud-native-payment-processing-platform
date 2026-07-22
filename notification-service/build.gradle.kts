/*
 * notification-service — simulated email logging + real webhook delivery on payment
 * lifecycle events, with an explicit Kafka retry topic and dead-letter topic for
 * webhook delivery failures (D10's "declared explicitly, no auto-create" policy,
 * extended to a producer of its own topics for the first time in this platform).
 *
 * Deliberately no REST API, no Spring Security, no OpenFeign (same scope discipline as
 * transaction-service/audit-service, D42): its only inbound interface is Kafka. Outbound
 * webhook delivery uses Spring Web's RestClient (already pulled by
 * spring-boot-starter-web) rather than OpenFeign — OpenFeign is for typed internal
 * clients to known services (D32's MerchantClient); this is one HTTP POST to an
 * arbitrary, merchant-configured external URL, which RestClient models directly.
 */
plugins {
    id("paymentflow.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    // Concrete Micrometer registry backend + distributed tracing (M13).
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")

    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation(libs.awaitility)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
