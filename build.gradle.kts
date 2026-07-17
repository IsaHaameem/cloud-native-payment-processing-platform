/*
 * Root build file.
 *
 * Intentionally thin: cross-cutting Java configuration lives in the
 * `paymentflow.java-conventions` convention plugin (see ./build-logic), not here.
 * This file only defines coordinates shared by every project and a convenience
 * task to surface the module graph.
 */

allprojects {
    group = "com.paymentflow"
    version = "0.0.1-SNAPSHOT"
}

tasks.register("modules") {
    group = "help"
    description = "Lists all modules registered in this monorepo."
    val names = subprojects.map { it.path }.sorted()
    doLast {
        println("Registered modules (${names.size}):")
        names.forEach { println("  $it") }
    }
}
