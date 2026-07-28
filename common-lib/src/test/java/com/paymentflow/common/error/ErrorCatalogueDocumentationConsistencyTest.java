package com.paymentflow.common.error;

import com.paymentflow.common.dto.error.ErrorType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps {@code docs/ERRORS.md} honest (M21.4) — the same discipline
 * {@code ReadApiDocumentationConsistencyTest} applies to the read-API guide and
 * {@code WebhookDocumentationConsistencyTest} to the webhook guide.
 *
 * <p>§5/M21 task 3 asks for the catalogue to be "one table that is the source of truth for
 * both the docs site and the SDKs". A markdown table maintained by hand is a source of truth
 * for about one milestone; what makes it durable is that adding a code without documenting
 * it — or documenting one that does not exist — fails the build.
 *
 * <p>The assertion runs in both directions deliberately. Forgetting to document a new code
 * is the obvious failure. The reverse is subtler and worse: a row describing a code that was
 * renamed or removed reads as authoritative, and an integrator writing a handler for it is
 * writing dead code that will never fire.
 */
class ErrorCatalogueDocumentationConsistencyTest {

    private static final Path CATALOGUE = Path.of("..", "docs", "ERRORS.md");

    private static String read() throws IOException {
        return Files.readString(CATALOGUE, StandardCharsets.UTF_8);
    }

    @Test
    void everyPublishedCodeIsDocumentedWithItsStatusAndType() throws IOException {
        String doc = read();

        for (ErrorCode code : ErrorCatalogue.published()) {
            // The row form is `| `CODE` | status | `type` | meaning |`. Asserted as three
            // separate containments rather than one regex over the whole row, so a failure
            // says which of the three is wrong instead of "the row does not match".
            assertThat(doc)
                    .describedAs("%s is missing from docs/ERRORS.md", code.code())
                    .contains("`" + code.code() + "`");

            String row = rowFor(doc, code.code());
            assertThat(row)
                    .describedAs("%s is documented with the wrong HTTP status", code.code())
                    .contains("| " + code.httpStatus() + " |");
            assertThat(row)
                    .describedAs("%s is documented with the wrong type", code.code())
                    .contains("`" + code.type().wireName() + "`");
        }
    }

    @Test
    void theCatalogueDocumentsNoCodeThatDoesNotExist() throws IOException {
        List<String> documented = read().lines()
                .filter(line -> line.startsWith("| `") && line.contains("_"))
                .map(line -> line.substring(3, line.indexOf('`', 3)))
                // Rows in the *type* table are lowercase wire names, not codes.
                .filter(name -> name.equals(name.toUpperCase(Locale.ROOT)))
                .toList();

        List<String> real = ErrorCatalogue.published().stream().map(ErrorCode::code).toList();

        assertThat(documented)
                .describedAs("docs/ERRORS.md documents codes the platform cannot return")
                .isNotEmpty()
                .allSatisfy(name -> assertThat(real).contains(name));
    }

    @Test
    void everyErrorTypeIsDocumented() throws IOException {
        String doc = read();

        for (ErrorType type : ErrorType.values()) {
            assertThat(doc)
                    .describedAs("the %s type is missing from docs/ERRORS.md", type.wireName())
                    .contains("`" + type.wireName() + "`");
        }
    }

    @Test
    void everyPublishedCodeHasADocUrlPointingAtItsOwnAnchor() {
        for (ErrorCode code : ErrorCatalogue.published()) {
            // The link is derived, not declared, so this cannot catch a typo — what it does
            // catch is a change to the derivation that silently repoints every link at once.
            assertThat(code.docUrl())
                    .isEqualTo(ErrorCatalogue.DOC_BASE_URL + "#" + code.code().toLowerCase(Locale.ROOT));
        }
    }

    @Test
    void theTwoConflictCodesAndTheTwoRateLimitCodesAreDistinguishedInProse() throws IOException {
        String doc = read();

        // Not a formatting check. These four codes share two status codes between them, and
        // the whole reason they are separate codes is that the correct client behaviour
        // differs — retryable versus not, seconds versus midnight. A catalogue that listed
        // them without saying so would be technically complete and practically useless.
        assertThat(doc).contains("Two 409s are not the same");
        assertThat(doc).contains("Two 429s are not the same");
    }

    private static String rowFor(String doc, String code) {
        return doc.lines()
                .filter(line -> line.startsWith("| `" + code + "`"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no catalogue row for " + code));
    }
}
