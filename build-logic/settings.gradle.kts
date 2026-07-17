/*
 * Settings for the included build that holds shared convention plugins.
 * Keeping build logic in its own composite build (rather than buildSrc) keeps
 * the main build's configuration cache stable and makes the plugins reusable.
 */
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "build-logic"
