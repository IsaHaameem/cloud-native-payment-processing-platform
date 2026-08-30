/*
 * The PaymentFlow Java SDK is its own Gradle build, not a module of the platform monorepo.
 *
 * It sits beside `sdks/node` and `sdks/python` and follows the same rule those do (D136): a
 * contributor building the platform needs neither this build nor its toolchain, and this build
 * needs nothing from the platform's. It is a published library with its own release cadence and
 * its own version, and wrapping it in the monorepo's `./gradlew build` would make every SDK
 * change a change to the platform build's critical path.
 *
 * The generated-equivalent layer (`dev.paymentflow.model.Contract` / `Operations` /
 * `Vocabularies` and the response records) is hand-written here and verified against
 * `../shared/fixtures/*.json` — the language-neutral golden fixtures the Node and Python SDKs
 * already assert against — by `ContractParityTest`. A `JavaEmitter` in `:sdks:shared` is a
 * possible future refinement; the fixture parity test is the guarantee in the meantime.
 */
rootProject.name = "paymentflow-java"
