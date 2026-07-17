/*
 * common-lib — cross-cutting building blocks shared by services: exception
 * hierarchy, standard error envelope, correlation-id filter, structured JSON
 * logging config, and security utilities. Contents arrive in M1.
 */
plugins {
    id("paymentflow.java-conventions")
}

dependencies {
    api(platform(project(":platform-bom")))
    api(project(":common-dto"))
}
