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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
