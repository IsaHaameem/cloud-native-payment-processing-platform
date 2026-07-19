/*
 * identity-service — users, authentication (BCrypt), JWT access + refresh tokens, and
 * role-based access control. The first bootable Spring Boot application in the platform.
 */
plugins {
    id("paymentflow.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(project(":platform-bom")))
    implementation(project(":common-lib"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Concrete Micrometer registry backend (M13) — closes the gap M8 flagged: the
    // /actuator/prometheus endpoint has been exposed since M8/M9 but had nothing
    // behind it (Boot's default CompositeMeterRegistry has no children).
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    // Distributed tracing (M13/D26): OTLP export to Tempo. Boot 4 ships a dedicated
    // starter bundling the OTel SDK + Micrometer Tracing bridge + OTLP exporter —
    // auto-configured entirely from management.tracing.*/management.opentelemetry.*
    // properties, no custom config class needed.
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")

    // Boot 4 modularized auto-configuration: FlywayAutoConfiguration lives in
    // spring-boot-flyway (which brings flyway-core), not in plain flyway-core.
    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 split the servlet MockMvc test slice into its own module.
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    // Testcontainers 2.x renamed all modules with a `testcontainers-` prefix.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
