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

    // Generates auto-configuration metadata so condition evaluation is fast/lazy.
    // The annotationProcessor configuration doesn't extend implementation, so it needs
    // the BOM applied explicitly to resolve the managed version.
    annotationProcessor(platform(project(":platform-bom")))
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
