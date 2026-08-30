package dev.paymentflow;

import dev.paymentflow.internal.Json;
import dev.paymentflow.model.Contract;
import dev.paymentflow.model.Operations;
import dev.paymentflow.model.Operations.OperationDescriptor;
import dev.paymentflow.model.Vocabularies;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java SDK's generated-equivalent layer against {@code ../shared/fixtures/*.json} — the same
 * language-neutral golden fixtures the Node and Python SDKs assert against. This is what keeps
 * {@code Contract}, {@code Operations}, {@code Vocabularies} and the response records honest
 * without a {@code JavaEmitter} in {@code :sdks:shared}.
 */
class ContractParityTest {

    private static final Path FIXTURES = Path.of("..", "shared", "fixtures");

    private static Map<String, Object> fixture(String name) throws Exception {
        return Json.parseObject(Files.readString(FIXTURES.resolve(name), StandardCharsets.UTF_8));
    }

    @Test
    void theContractConstantsMatch() throws Exception {
        Map<String, Object> c = fixture("contract.json");
        assertEquals(c.get("apiVersion"), Contract.API_VERSION);
        assertEquals(c.get("baseUrl"), Contract.DEFAULT_BASE_URL);
        assertEquals(c.get("title"), Contract.API_TITLE);
        assertEquals(((Number) c.get("operationCount")).intValue(), Operations.ALL.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyOperationMatchesTheFixtureExactly() throws Exception {
        Map<String, Object> ops = fixture("operations.json");
        assertEquals(new TreeSet<>(ops.keySet()), new TreeSet<>(Operations.ALL.keySet()),
                "the Java SDK must cover exactly the published operations");

        for (Map.Entry<String, Object> entry : ops.entrySet()) {
            Map<String, Object> f = (Map<String, Object>) entry.getValue();
            OperationDescriptor d = Operations.ALL.get(entry.getKey());
            assertNotNull(d, entry.getKey());
            assertEquals(f.get("method"), d.method(), entry.getKey() + ".method");
            assertEquals(f.get("path"), d.path(), entry.getKey() + ".path");
            assertEquals(f.get("tag"), d.tag(), entry.getKey() + ".tag");
            assertEquals(f.get("successStatus"), d.successStatus(), entry.getKey() + ".successStatus");
            assertEquals(f.get("hasRequestBody"), d.hasRequestBody(), entry.getKey() + ".hasRequestBody");
            assertEquals(f.get("queryParameters"), d.queryParameters(), entry.getKey() + ".queryParameters");
            assertEquals(f.get("requiredHeaders"), d.requiredHeaders(), entry.getKey() + ".requiredHeaders");
        }
    }

    @Test
    void theIdempotencyKeyIsRequiredForExactlyTheFivePaymentMutations() {
        long withKey = Operations.ALL.values().stream()
                .filter(d -> d.requiredHeaders().contains("Idempotency-Key"))
                .count();
        assertEquals(5, withKey);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyVocabularyMatchesTheFixture() throws Exception {
        Map<String, Object> enums = fixture("enums.json");
        for (Map.Entry<String, Object> entry : enums.entrySet()) {
            String constant = constantName(entry.getKey());
            Field field;
            try {
                field = Vocabularies.class.getField(constant);
            } catch (NoSuchFieldException e) {
                throw new AssertionError("Vocabularies has no " + constant + " for enum " + entry.getKey());
            }
            try {
                assertEquals(entry.getValue(), field.get(null), entry.getKey());
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyResponseRecordHasExactlyTheFixturesFields() throws Exception {
        Map<String, Object> models = fixture("models.json");
        int checked = 0;
        for (Map.Entry<String, Object> entry : models.entrySet()) {
            String name = entry.getKey();
            boolean isPublicResponse = (name.endsWith("Response") || name.equals("CurrencyBalance")
                    || name.equals("ApiFieldError"))
                    && !name.startsWith("CursorPage") && !name.startsWith("PageResponse");
            if (!isPublicResponse) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName("dev.paymentflow.model." + name);
            } catch (ClassNotFoundException e) {
                throw new AssertionError("no Java record dev.paymentflow.model." + name);
            }
            assertTrue(type.isRecord(), name + " must be a record");
            TreeSet<String> expected = new TreeSet<>((List<String>) entry.getValue());
            TreeSet<String> actual = new TreeSet<>();
            for (RecordComponent component : type.getRecordComponents()) {
                actual.add(component.getName());
            }
            assertEquals(expected, actual, name + " fields");
            checked++;
        }
        assertTrue(checked >= 18, "expected to check every response model, checked " + checked);
    }

    /** The same derivation the TypeScript and Python emitters use for the companion constant. */
    private static String constantName(String typeName) {
        return typeName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT) + "_VALUES";
    }
}
