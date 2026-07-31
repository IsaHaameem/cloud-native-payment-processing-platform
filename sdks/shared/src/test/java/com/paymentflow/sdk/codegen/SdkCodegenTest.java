package com.paymentflow.sdk.codegen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the generator writes, and what it refuses to write (M22.1).
 *
 * <p>The emitters are tested through {@link SdkCodegen} rather than individually, because the
 * property worth asserting is about the pair: the two languages must describe the same
 * contract. A test of one emitter in isolation can only say that it is self-consistent.
 */
class SdkCodegenTest {

    /** The real published contract. Declared as an input of this module's `test` task. */
    private static final Path BASELINE = Path.of("..", "..", "docs", "openapi.yaml");

    private static String baseline() throws IOException {
        return Files.readString(BASELINE, StandardCharsets.UTF_8);
    }

    private static final String SPEC = """
            openapi: 3.1.0
            info:
              title: PaymentFlow API
              version: "2026-08-01"
            servers:
            - url: https://api.paymentflow.dev
            paths:
              /v1/payments:
                get:
                  operationId: listPayments
                  tags:
                  - Payments
                  summary: List your payments.
                  parameters:
                  - name: limit
                    in: query
                    schema:
                      type: integer
                  - name: PaymentFlow-Version
                    in: header
                    schema:
                      type: string
                  responses:
                    "200":
                      content:
                        application/json:
                          schema:
                            $ref: "#/components/schemas/PaymentResponse"
            components:
              schemas:
                PaymentResponse:
                  type: object
                  description: A payment.
                  properties:
                    amountMinor:
                      type: integer
                      format: int64
                      description: The amount, in minor units.
                    metadata:
                      type: object
                      additionalProperties:
                        type: string
                      description: Your own key-value pairs.
                    status:
                      type: string
                      description: Where the payment stands.
                      enum:
                      - created
                      - authorized
            """;

    @Test
    void everyGeneratedTreeIsWrittenAndNothingElseIs() {
        SdkCodegen.Result result = SdkCodegen.generate(SPEC);

        assertThat(result.files().keySet()).containsExactlyInAnyOrder(
                "sdks/node/src/generated/contract.ts",
                "sdks/node/src/generated/models.ts",
                "sdks/node/src/generated/operations.ts",
                "sdks/python/src/paymentflow/_generated/__init__.py",
                "sdks/python/src/paymentflow/_generated/contract.py",
                "sdks/python/src/paymentflow/_generated/models.py",
                "sdks/python/src/paymentflow/_generated/operations.py",
                "sdks/shared/fixtures/contract.json",
                "sdks/shared/fixtures/enums.json",
                "sdks/shared/fixtures/models.json",
                "sdks/shared/fixtures/operations.json");

        // Every file lands under a directory the generator declares it owns. The check path
        // treats anything else in those directories as an orphan, so a file written outside
        // them would be invisible to the freshness gate forever.
        assertThat(result.files().keySet()).allSatisfy(path ->
                assertThat(SdkCodegen.generatedDirectories()).anySatisfy(directory ->
                        assertThat(path).startsWith(directory + "/")));
    }

    @Test
    void bothLanguagesDescribeTheSameOperationsModelsAndVocabularies() {
        Map<String, String> files = SdkCodegen.generate(SPEC).files();

        String typescript = files.get("sdks/node/src/generated/models.ts");
        String python = files.get("sdks/python/src/paymentflow/_generated/models.py");

        for (String name : new String[]{"PaymentResponse", "PaymentResponseStatus"}) {
            assertThat(typescript).describedAs("TypeScript declares %s", name).contains(name);
            assertThat(python).describedAs("Python declares %s", name).contains(name);
        }
        // Same derivation of the companion constant in both, which is what lets the two test
        // suites assert against one fixture without either transcribing the other's naming.
        assertThat(typescript).contains("PAYMENT_RESPONSE_STATUS_VALUES");
        assertThat(python).contains("PAYMENT_RESPONSE_STATUS_VALUES");

        assertThat(files.get("sdks/node/src/generated/operations.ts")).contains("listPayments");
        assertThat(files.get("sdks/python/src/paymentflow/_generated/operations.py")).contains("listPayments");
    }

    @Test
    void enumsAreEmittedOpenSoAnUnknownValueIsNotAnError() {
        Map<String, String> files = SdkCodegen.generate(SPEC).files();

        // §9 requires clients to tolerate enum values they have never heard of — that is what
        // makes "a new enum value is additive" true rather than aspirational. TypeScript keeps
        // the union open with `(string & {})`; Python widens the alias to `str`. Both accept
        // an unknown value, and both still publish the documented set beside it.
        assertThat(files.get("sdks/node/src/generated/models.ts"))
                .contains("(string & {})");
        assertThat(files.get("sdks/python/src/paymentflow/_generated/models.py"))
                .contains("PaymentResponseStatus = str");
    }

    @Test
    void onlyQueryParametersReachTheOperationDescriptors() {
        Map<String, String> files = SdkCodegen.generate(SPEC).files();

        // M22.0 put `PaymentFlow-Version` on every operation as a header parameter. A client
        // that treated it as a query parameter would send `?PaymentFlow-Version=…`, which is
        // ignored, so the request would silently run on the wrong revision.
        assertThat(files.get("sdks/node/src/generated/operations.ts"))
                .contains("queryParameters: ['limit']")
                .doesNotContain("queryParameters: ['limit', 'PaymentFlow-Version']");
        assertThat(files.get("sdks/shared/fixtures/operations.json"))
                .contains("\"limit\"")
                .doesNotContain("PaymentFlow-Version");
    }

    @Test
    void aFieldNamedAfterAPythonKeywordIsEmittedInTheFormThatCompiles() {
        Map<String, String> files = SdkCodegen.generate("""
                openapi: 3.1.0
                info:
                  title: PaymentFlow API
                  version: "2026-08-01"
                servers:
                - url: https://api.paymentflow.dev
                paths: {}
                components:
                  schemas:
                    UsageSummaryResponse:
                      type: object
                      properties:
                        from:
                          type: string
                          description: The start of the window.
                        to:
                          type: string
                          description: The end of the window.
                """).files();

        // The analytics and usage summaries both publish their window as `from`/`to`, and
        // `from` is a Python keyword: a class body declaring it is a syntax error, so the whole
        // module fails to import. Renaming the field was not an option — the wire name is what
        // the response actually contains — so the functional TypedDict form is used instead.
        String python = files.get("sdks/python/src/paymentflow/_generated/models.py");
        assertThat(python).contains("UsageSummaryResponse = TypedDict(");
        assertThat(python).contains("\"from\": str");
        assertThat(python).doesNotContain("    from: str");
        // The field descriptions move into the type's own docstring rather than being dropped.
        assertThat(python).contains("``from``: The start of the window.");

        // TypeScript has no such problem, so it keeps the readable form.
        assertThat(files.get("sdks/node/src/generated/models.ts"))
                .contains("export interface UsageSummaryResponse")
                .contains("from?: string;");
    }

    @Test
    void everyGeneratedFileEndsItsLinesTheWayTheRepositoryStoresThem() {
        Map<String, String> files = SdkCodegen.generate(SPEC).files();

        // `.gitattributes` declares `* text=auto eol=lf`, so a checkout puts LF on disk on
        // every platform. An emitter writing CRLF therefore leaves a Windows contributor with
        // modified files after running the generator on a clean tree — a diff that shows no
        // change, on files nobody edited. Jackson's default pretty printer does exactly that:
        // it indents with the *system* linefeed, so the JSON fixtures came out CRLF while the
        // TypeScript and Python trees, built line by line from "\n", came out LF.
        assertThat(files).allSatisfy((path, content) ->
                assertThat(content).describedAs("%s uses LF line endings", path).doesNotContain("\r"));

        // And every file ends with one, so appending to it is a one-line diff rather than two.
        assertThat(files).allSatisfy((path, content) ->
                assertThat(content).describedAs("%s ends with a newline", path).endsWith("\n"));
    }

    @Test
    void generatingTwiceProducesIdenticalOutput() {
        // The freshness gate compares bytes. A generator whose output depended on map ordering
        // would report every clean checkout as stale, on some runs and not others, and the
        // gate would be switched off within a week.
        assertThat(SdkCodegen.generate(SPEC).files()).isEqualTo(SdkCodegen.generate(SPEC).files());
    }

    // ── Against the real contract ───────────────────────────────────────────────────────

    @Test
    void theRealPublishedContractGeneratesWithNoUnsupportedConstructs() throws IOException {
        SdkCodegen.Result result = SdkCodegen.generate(baseline());

        // The assertion the synthetic specs above cannot make: that this generator understands
        // the document as it actually is today. `verifySdkSources` enforces the same thing in
        // the build, but as a task it only runs when someone runs it; here it is part of the
        // suite that runs on every `check`.
        assertThat(result.unsupported()).isEmpty();
    }

    @Test
    void theRealPublishedContractProducesASubstantialSdk() throws IOException {
        Map<String, String> files = SdkCodegen.generate(baseline()).files();

        // Not a count of exactly 31 operations: an assertion that has to be edited every time
        // an endpoint is added is an assertion people edit without reading. What is worth
        // pinning is that the generator produced a real SDK rather than an empty shell, which
        // is how a silently-broken reader would look.
        assertThat(files.get("sdks/shared/fixtures/operations.json")).contains("createPayment");
        assertThat(files.get("sdks/node/src/generated/models.ts")).contains("export interface PaymentResponse");
        assertThat(files.get("sdks/python/src/paymentflow/_generated/models.py")).contains("class PaymentResponse");
        // M22.0's contribution, which §7.1's typed error hierarchy is built on.
        assertThat(files.get("sdks/shared/fixtures/enums.json")).contains("ApiErrorType");
    }
}
