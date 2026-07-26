/*
 * analytics-service — per-merchant/currency payment aggregates for reporting; idempotent
 * consumer of payment.events with optimistic-lock retry on the shared aggregate row
 * (mirrors transaction-service's LedgerService pattern, M6).
 *
 * D42 originally scoped this service to a Kafka stream and nothing else, deferring a read
 * API until a real consumer existed. M19.6 is that consumer: the service now serves
 * /v1/analytics with Spring Security wired for InternalContextFilter only (no OAuth2
 * resource server, D133). M20 adds the API request log and its usage aggregates, making it
 * the owner of the platform's highest-volume table. Still no OpenFeign — it makes no
 * synchronous outbound call to any service.
 */
plugins {
    id("paymentflow.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    // M19.6: the analytics query API. Security for InternalContextFilter only —
    // no OAuth2 resource server (D133).
    implementation("org.springframework.boot:spring-boot-starter-security")
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
