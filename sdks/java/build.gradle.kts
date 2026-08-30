/*
 * The PaymentFlow Java SDK (M26).
 *
 * ── Java 17, not the platform's 25 ──
 *
 * An SDK runs in its users' environments, not in ours. §7.2 fixes the floor at 17: it is the
 * oldest LTS a new service is realistically started on today, `java.net.http.HttpClient` and
 * `java.time` cover everything this client needs, and nothing here would read better on a newer
 * one. Compiled with `--release 17` so the 17 API surface is enforced by the compiler rather
 * than trusted — the build runs on whatever JDK is present (25 in CI), and the bytecode is 17.
 *
 * ── Zero runtime dependencies ──
 *
 * The same rule the Node SDK keeps and the Python SDK bends only for `httpx`. A payments SDK
 * that drags a transitive tree is a supply-chain liability for every integrator. The JDK has an
 * HTTP client, an HMAC, and a UUID generator; the one thing it has no answer for is JSON, so
 * `dev.paymentflow.internal.Json` is a small hand-written reader/writer (the contract's data is
 * strings, integers, booleans, string maps and nested objects — nothing exotic) with its own
 * test. JUnit is `testImplementation` only and never ships.
 */
plugins {
    `java-library`
    `maven-publish`
    signing
}

group = "dev.paymentflow"
version = "0.1.0"
description = "The official PaymentFlow API client for Java."

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 17
    // Keep parameter names in the bytecode: the resource methods read them in error messages,
    // and a caller stepping through in a debugger should see `paymentId`, not `arg0`.
    options.compilerArgs.add("-parameters")
    options.compilerArgs.add("-Xlint:all,-serial")
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
    // ContractParityTest reads ../shared/fixtures and ../../notification-service/.../signature
    // vectors. They live outside this build; declare them so a fixture change re-runs the test
    // instead of Gradle reporting it UP-TO-DATE — the same trap `:sdks:shared`'s build file
    // documents for `SdkCodegenTest`.
    inputs.dir(layout.projectDirectory.dir("../shared/fixtures")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("../../notification-service/src/test/resources/signature-vectors"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// ── examples ────────────────────────────────────────────────────────────────────────────────
//
// Compiled, never run, and never published. They import the SDK exactly as an integrator does,
// so an API change that breaks one fails `check` here rather than being found by someone
// copying it out of the README.
sourceSets {
    create("examples") {
        java.srcDir("examples/src/main/java")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}
tasks.named("check") { dependsOn("compileExamplesJava") }

// ── publishing (publish-ready, not published) ───────────────────────────────────────────────
//
// `./gradlew publishToMavenLocal` stages a complete artifact — jar, sources, javadoc, a POM
// with the metadata Maven Central requires — into ~/.m2 for inspection. Nothing here pushes to
// a remote: the release workflow in `.github/workflows/` does that, only on a tag, only with
// credentials that live in repository secrets. See `sdks/PUBLISHING.md`.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "paymentflow"
            pom {
                name.set("PaymentFlow Java SDK")
                description.set(project.description)
                url.set("https://github.com/IsaHaameem/cloud-native-payment-processing-platform")
                licenses {
                    license {
                        name.set("Proprietary — all rights reserved")
                        url.set("https://github.com/IsaHaameem/cloud-native-payment-processing-platform")
                    }
                }
                developers {
                    developer {
                        id.set("IsaHaameem")
                        name.set("Isa Hameem")
                    }
                }
                scm {
                    url.set("https://github.com/IsaHaameem/cloud-native-payment-processing-platform")
                    connection.set("scm:git:https://github.com/IsaHaameem/cloud-native-payment-processing-platform.git")
                }
            }
        }
    }
}

// Signing is required by Maven Central and is a no-op locally: it runs only when a signing key
// is provided (the release workflow sets `ORG_GRADLE_PROJECT_signingInMemoryKey`).
signing {
    val signingKey: String? by project
    val signingPassword: String? by project
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}
