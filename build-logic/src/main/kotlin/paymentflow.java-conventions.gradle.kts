/*
 * Shared Java conventions applied by every JVM module in the monorepo.
 *
 * Centralizing this here means individual module build files stay tiny and we
 * never duplicate toolchain/encoding/test configuration. Any module that needs
 * Java simply does: plugins { id("paymentflow.java-conventions") }
 */

plugins {
    `java-library`
}

java {
    toolchain {
        // Compile & run against Java 25 (LTS). Foojay auto-downloads it if the
        // developer's machine has a different JDK — builds stay reproducible.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // -parameters keeps parameter names at runtime (needed by Spring & Jackson).
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}
