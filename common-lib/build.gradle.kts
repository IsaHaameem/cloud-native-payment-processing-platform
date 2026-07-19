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
    // M13: ObservabilityAutoConfiguration's MeterRegistryCustomizer bean. compileOnly
    // for the same reason as the web/validation deps above — every service already
    // brings spring-boot-starter-actuator (+ a concrete Micrometer registry) itself;
    // common-lib only needs these types to compile against, not to force as a
    // transitive dependency onto a module that doesn't want actuator at all.
    compileOnly("io.micrometer:micrometer-core")
    compileOnly("org.springframework.boot:spring-boot-micrometer-metrics")

    // Generates auto-configuration metadata so condition evaluation is fast/lazy.
    // The annotationProcessor configuration doesn't extend implementation, so it needs
    // the BOM applied explicitly to resolve the managed version.
    annotationProcessor(platform(project(":platform-bom")))
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
