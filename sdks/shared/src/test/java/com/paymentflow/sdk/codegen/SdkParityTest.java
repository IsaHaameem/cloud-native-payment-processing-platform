package com.paymentflow.sdk.codegen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-language parity between the Node and Python SDKs (M22.7).
 *
 * <p><b>Why this lives here, in Java.</b> The two SDKs are meant to be one client in two
 * languages, and each one's own suite can only ever say that it is self-consistent — Node's
 * tests pass whether or not Python exists, and Python's likewise. Something has to compare
 * them, and the only place that can is a test which needs neither toolchain to run. D136 keeps
 * `node` and `python` out of `./gradlew build`'s prerequisites, so a parity check written in
 * either language would be a check a contributor without that language never runs, and CI's
 * two SDK jobs run in parallel and cannot see each other.
 *
 * <p>It reads both source trees as text. That is cruder than importing them and is the point:
 * it costs nothing, it runs everywhere, and the properties worth checking — which operations
 * each SDK covers, which namespaces group them, which error classes exist — are all visible in
 * the source. What it deliberately does not do is compare *behaviour*; that is what the shared
 * golden fixtures in {@code sdks/shared/fixtures} are for, and both suites already assert
 * against those.
 *
 * <p><b>Where the two are allowed to differ.</b> §7.1 says the design is identical across
 * languages and only idiom varies, so every divergence is listed here explicitly rather than
 * tolerated by a loose assertion. Today there are three, all forced by Python: {@code del} is a
 * keyword so the endpoint delete is {@code delete}; {@code PermissionError} is a builtin so the
 * class is {@code PermissionDeniedError} (D178); and method names are {@code snake_case}.
 */
class SdkParityTest {

    private static final Path NODE = Path.of("..", "node");
    private static final Path PYTHON = Path.of("..", "python");

    /** `OPERATIONS.createPayment` in TypeScript, `OPERATIONS["createPayment"]` in Python. */
    private static final Pattern NODE_OPERATION = Pattern.compile("OPERATIONS\\.(\\w+)");
    private static final Pattern PYTHON_OPERATION = Pattern.compile("OPERATIONS\\[\"(\\w+)\"]");

    /**
     * The namespaces, and the source file that implements each in either language.
     *
     * <p>A map rather than a derivation, because the file split is a decision rather than a
     * fact — and one worth stating: a namespace that quietly moved into another module would
     * otherwise still pass a check that only counted operations.
     */
    private static final Map<String, String[]> NAMESPACES = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("payments", new String[]{"payments.ts", "payments.py"}),
            Map.entry("refunds", new String[]{"refunds.ts", "refunds.py"}),
            Map.entry("balance", new String[]{"balance.ts", "balance.py"}),
            Map.entry("events", new String[]{"events.ts", "events.py"}),
            Map.entry("reporting", new String[]{"reporting.ts", "reporting.py"}),
            Map.entry("webhooks", new String[]{"webhooks.ts", "webhooks.py"}),
            Map.entry("testHelpers", new String[]{"test-helpers.ts", "test_helpers.py"})));

    // ── Operation coverage ──────────────────────────────────────────────────────────────

    @Test
    void bothSdksCoverEveryPublishedOperationAndNeitherCoversOneTheOtherDoesNot() throws IOException {
        Set<String> published = new TreeSet<>(SdkCodegen.generate(baseline()).files().keySet().isEmpty()
                ? Set.of()
                : operationIds());
        Set<String> node = operationsIn(nodeResources(), NODE_OPERATION);
        Set<String> python = operationsIn(pythonResources(), PYTHON_OPERATION);

        // Asserted against the contract first, so this cannot pass by both SDKs covering the
        // same empty set — the failure mode of every symmetric comparison.
        assertThat(published).describedAs("the contract publishes operations").isNotEmpty();
        assertThat(node).describedAs("operations the Node SDK cannot call").isEqualTo(published);
        assertThat(python).describedAs("operations the Python SDK cannot call").isEqualTo(published);
    }

    @Test
    void everyNamespaceImplementsTheSameOperationsInBothLanguages() throws IOException {
        for (Map.Entry<String, String[]> namespace : NAMESPACES.entrySet()) {
            Set<String> node = operationsIn(
                    List.of(read(NODE.resolve("src/resources").resolve(namespace.getValue()[0]))), NODE_OPERATION);
            Set<String> python = operationsIn(
                    List.of(read(PYTHON.resolve("src/paymentflow/resources").resolve(namespace.getValue()[1]))),
                    PYTHON_OPERATION);

            assertThat(node).describedAs("%s is implemented in Node", namespace.getKey()).isNotEmpty();
            // Not merely "both cover everything between them": an operation that migrated from
            // one namespace to another in one language only would still satisfy that, and it
            // would mean the same call is spelled `client.payments.x` in one SDK and
            // `client.events.x` in the other.
            assertThat(python)
                    .describedAs("%s groups the same operations in both languages", namespace.getKey())
                    .isEqualTo(node);
        }
    }

    // ── The public surface ──────────────────────────────────────────────────────────────

    @Test
    void theErrorHierarchyIsTheSameInBothLanguagesExceptWherePythonCannotSpellIt() throws IOException {
        Set<String> node = classesIn(read(NODE.resolve("src/errors.ts")), Pattern.compile("export class (\\w+)"));
        Set<String> python = classesIn(read(PYTHON.resolve("src/paymentflow/_errors.py")),
                Pattern.compile("^class (\\w+)\\(", Pattern.MULTILINE));

        assertThat(node).describedAs("the Node hierarchy exists").isNotEmpty();

        // The one rename, and the reason for it. `PermissionError` is a Python builtin, so
        // exporting one of ours under that name would mean `from paymentflow import
        // PermissionError` silently stops a module catching filesystem errors (D178).
        Set<String> expected = new TreeSet<>(node);
        assertThat(expected.remove("PermissionError")).describedAs("Node names PermissionError").isTrue();
        expected.add("PermissionDeniedError");

        assertThat(python).describedAs("the two error hierarchies agree, modulo the builtin clash")
                .isEqualTo(expected);
    }

    @Test
    void bothSdksPublishTheSameResponseModelsAndNeitherPublishesARequestModel() throws IOException {
        Set<String> node = namesReExportedFrom(read(NODE.resolve("src/index.ts")),
                "export type {", "} from './generated/models.js';");
        Set<String> python = namesBetween(read(PYTHON.resolve("src/paymentflow/__init__.py")),
                "from ._generated.models import (", ")");

        assertThat(node).describedAs("Node re-exports response models").isNotEmpty();
        // The enum aliases are TypeScript-only: Python widens each vocabulary to `str`, so
        // there is no name to export. Compared on the response models, which both have.
        Set<String> responseModels = node.stream()
                .filter(name -> name.endsWith("Response") || name.endsWith("Error") || name.equals("CurrencyBalance"))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> pythonModels = python.stream()
                .filter(name -> name.endsWith("Response") || name.endsWith("Error") || name.equals("CurrencyBalance"))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(pythonModels).describedAs("both SDKs publish the same response models").isEqualTo(responseModels);

        // Neither publishes a request model. What a caller passes is the hand-written parameter
        // type or keyword argument, which under D170 states requirements the generated one does
        // not — publishing the generated model beside it would offer two answers to one
        // question, and the wrong one would compile.
        for (String name : List.of("CreatePaymentRequest", "RefundRequest", "CreateWebhookEndpointRequest")) {
            assertThat(node).describedAs("Node does not publish %s", name).doesNotContain(name);
            assertThat(python).describedAs("Python does not publish %s", name).doesNotContain(name);
        }
    }

    @Test
    void neitherSdkReExportsTheGeneratedRuntimeValues() throws IOException {
        String node = read(NODE.resolve("src/index.ts"));
        String python = read(PYTHON.resolve("src/paymentflow/__init__.py"));

        // The operation table and the enum value lists are implementation details in both. A
        // caller reaching for them would be depending on the generator's naming, which is the
        // whole thing the curated re-export lists exist to prevent (D172).
        // Anchored to the start of a line: `index.ts` quotes the wildcard form in its own
        // prose as the thing it exists to avoid, and an unanchored match found the explanation
        // rather than an export.
        assertThat(node).doesNotContain("export { OPERATIONS");
        assertThat(node).describedAs("no wildcard re-export of the generated tree")
                .doesNotContainPattern("(?m)^export \\* from ['\"]\\./generated");
        assertThat(python).doesNotContain("from ._generated.operations import")
                .doesNotContain("from ._generated.models import *");
        assertThat(python).describedAs("no enum value tuple is public").doesNotContain("_VALUES");
        assertThat(node).describedAs("no enum value array is public").doesNotContain("_VALUES");
    }

    // ── Behavioural constants ───────────────────────────────────────────────────────────

    @Test
    void theRetryAndWebhookConstantsAgreeAcrossLanguages() throws IOException {
        String nodeTransport = read(NODE.resolve("src/transport.ts"));
        String pythonTransport = read(PYTHON.resolve("src/paymentflow/_transport.py"));

        // Node counts in milliseconds and Python in seconds, which is each language's idiom;
        // what must agree is the interval those spell. A backoff cap that differed would make
        // the two SDKs behave differently under exactly the conditions nobody tests by hand.
        assertThat(nodeTransport).contains("BASE_BACKOFF_MS = 500").contains("MAX_BACKOFF_MS = 8_000");
        assertThat(pythonTransport).contains("_BASE_BACKOFF_SECONDS = 0.5").contains("_MAX_BACKOFF_SECONDS = 8.0");
        assertThat(nodeTransport).contains("MAX_HONOURED_RETRY_AFTER_MS = 60_000");
        assertThat(pythonTransport).contains("_MAX_HONOURED_RETRY_AFTER_SECONDS = 60.0");

        String nodeWebhooks = read(NODE.resolve("src/webhooks.ts"));
        String pythonWebhooks = read(PYTHON.resolve("src/paymentflow/_webhooks.py"));
        assertThat(nodeWebhooks).contains("DEFAULT_TOLERANCE_SECONDS = 300");
        assertThat(pythonWebhooks).contains("DEFAULT_TOLERANCE_SECONDS: Final[int] = 300");
        assertThat(nodeWebhooks).contains("SIGNATURE_HEADER = 'PaymentFlow-Signature'");
        assertThat(pythonWebhooks).contains("SIGNATURE_HEADER: Final[str] = \"PaymentFlow-Signature\"");
    }

    @Test
    void bothSdksVerifyAgainstThePlatformsOwnSignatureVectors() throws IOException {
        Path vectors = Path.of("..", "..", "notification-service", "src", "test", "resources",
                "signature-vectors", "webhook-signature-vectors.json");
        assertThat(vectors).describedAs("the shared vector file is where both SDKs expect it").exists();

        // The strongest available statement that the two implementations agree: they are each
        // checked against the same third artefact, which neither of them produced, and which
        // the platform's own signer is checked against too. Two SDKs that agreed only with each
        // other could still both be wrong.
        assertThat(read(NODE.resolve("test/webhooks.test.mjs")))
                .describedAs("the Node suite reads the shared vectors")
                .contains("signature-vectors/webhook-signature-vectors.json");
        assertThat(read(PYTHON.resolve("tests/test_webhooks.py")))
                .describedAs("the Python suite reads the shared vectors")
                .contains("webhook-signature-vectors.json");
    }

    @Test
    void neitherPackageIsConfiguredToBePublished() throws IOException {
        // Publishing to a public registry is irreversible and effectively claims a name. Both
        // packages carry the marker that refuses it, and this is the check that stops one of
        // them quietly losing it.
        assertThat(read(NODE.resolve("package.json"))).contains("\"private\": true");
        assertThat(read(PYTHON.resolve("pyproject.toml"))).contains("Private :: Do Not Upload");
    }

    @Test
    void bothReadmesDocumentTheSameNamespacesAndTheSameErrorClasses() throws IOException {
        String node = read(NODE.resolve("README.md"));
        String python = read(PYTHON.resolve("README.md"));

        // Documentation parity is not a nicety here: §7.1's promise is that an integrator who
        // knows one SDK knows the other, and the READMEs are where that promise is either kept
        // or quietly broken.
        for (String namespace : List.of("payments", "refunds", "balance", "events", "analytics", "usage")) {
            assertThat(node).describedAs("the Node README documents %s", namespace).contains(namespace);
            assertThat(python).describedAs("the Python README documents %s", namespace).contains(namespace);
        }
        for (String topic : List.of("Idempotency", "Pagination", "Webhooks", "Errors")) {
            assertThat(node).describedAs("the Node README covers %s", topic).containsIgnoringCase(topic);
            assertThat(python).describedAs("the Python README covers %s", topic).containsIgnoringCase(topic);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────

    private static Set<String> operationIds() throws IOException {
        String operations = SdkCodegen.generate(baseline()).files().get("sdks/shared/fixtures/operations.json");
        Set<String> ids = new TreeSet<>();
        Matcher matcher = Pattern.compile("^  \"(\\w+)\" : \\{", Pattern.MULTILINE).matcher(operations);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private static String baseline() throws IOException {
        return Files.readString(Path.of("..", "..", "docs", "openapi.yaml"), StandardCharsets.UTF_8);
    }

    private static List<String> nodeResources() throws IOException {
        return sourcesIn(NODE.resolve("src/resources"), ".ts");
    }

    private static List<String> pythonResources() throws IOException {
        return sourcesIn(PYTHON.resolve("src/paymentflow/resources"), ".py");
    }

    private static List<String> sourcesIn(Path directory, String extension) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.toString().endsWith(extension))
                    .sorted()
                    .map(SdkParityTest::readQuietly)
                    .toList();
        }
    }

    private static Set<String> operationsIn(List<String> sources, Pattern pattern) {
        Set<String> found = new TreeSet<>();
        for (String source : sources) {
            Matcher matcher = pattern.matcher(source);
            while (matcher.find()) {
                found.add(matcher.group(1));
            }
        }
        return found;
    }

    private static Set<String> classesIn(String source, Pattern pattern) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /**
     * The identifiers in every export block that ends with {@code close}.
     *
     * <p>Every one, not the first: {@code index.ts} re-exports the models and the enum aliases
     * as two blocks with the same closing line, and reading only one of them would compare
     * half a surface against a whole one.
     */
    private static Set<String> namesReExportedFrom(String source, String open, String close) {
        Set<String> names = new TreeSet<>();
        int from = 0;
        while (true) {
            int end = source.indexOf(close, from);
            if (end < 0) {
                return names;
            }
            int start = source.lastIndexOf(open, end);
            if (start >= 0) {
                names.addAll(namesBetween(source.substring(start, end + close.length()), open, close));
            }
            from = end + close.length();
        }
    }

    /** The identifiers listed in one import or export block, between two literal markers. */
    private static Set<String> namesBetween(String source, String open, String close) {
        int start = source.indexOf(open);
        if (start < 0) {
            return Set.of();
        }
        int end = source.indexOf(close, start);
        String block = end < 0 ? source.substring(start + open.length()) : source.substring(start + open.length(), end);
        Set<String> names = new LinkedHashSet<>();
        for (String line : block.split("[,\n]")) {
            String name = line.trim();
            if (!name.isEmpty() && name.matches("\\w+")) {
                names.add(name);
            }
        }
        return new TreeSet<>(names);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }
}
