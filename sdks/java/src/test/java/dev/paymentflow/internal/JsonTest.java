package dev.paymentflow.internal;

import dev.paymentflow.model.PaymentResponse;
import dev.paymentflow.model.WebhookEndpointResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void parsesTheScalarTypesTheContractUses() {
        assertEquals("hi", Json.parse("\"hi\""));
        assertEquals(1000L, Json.parse("1000"));
        assertInstanceOf(Long.class, Json.parse("1000"));
        assertEquals(0.9, Json.parse("0.9"));
        assertInstanceOf(Double.class, Json.parse("0.9"));
        assertEquals(Boolean.TRUE, Json.parse("true"));
        assertNull(Json.parse("null"));
    }

    @Test
    void parsesNestedObjectsAndArraysWithUnicodeAndEscapes() {
        Object parsed = Json.parse("{\"a\":[1,2,{\"b\":\"c\\n\\u00e9\"}],\"d\":null}");
        assertInstanceOf(Map.class, parsed);
        Map<?, ?> map = (Map<?, ?>) parsed;
        assertEquals(List.of(1L, 2L, Map.of("b", "c\né")), map.get("a"));
        assertTrue(map.containsKey("d"));
        assertNull(map.get("d"));
    }

    @Test
    void rejectsTrailingGarbage() {
        assertThrows(JsonException.class, () -> Json.parse("{} {}"));
    }

    @Test
    void writesCompactJsonAndCollapsesIntegralDoubles() {
        assertEquals("{\"amountMinor\":1000,\"currency\":\"USD\"}",
                Json.write(linked("amountMinor", 1000L, "currency", "USD")));
        assertEquals("1000", Json.write(1000.0));
        assertEquals("\"a\\\"b\"", Json.write("a\"b"));
    }

    @Test
    void mapsAnObjectToARecordAndIgnoresUnknownKeys() {
        String body = "{\"id\":\"pay_1\",\"amountMinor\":1000,\"status\":\"captured\","
                + "\"metadata\":{\"orderId\":\"A-1\"},\"somethingAddedLater\":42}";
        PaymentResponse payment = Json.toRecord(Json.parse(body), PaymentResponse.class);
        assertEquals("pay_1", payment.id());
        assertEquals(1000L, payment.amountMinor());
        assertEquals("captured", payment.status());
        assertEquals(Map.of("orderId", "A-1"), payment.metadata());
        assertNull(payment.refunds());
    }

    @Test
    void coercesNumbersToTheRecordComponentType() {
        // The wire may send an integral value with or without a fraction; the record decides.
        WebhookEndpointResponse endpoint = Json.toRecord(
                Json.parse("{\"consecutiveFailureCount\":3.0,\"enabled\":true}"), WebhookEndpointResponse.class);
        assertEquals(3L, endpoint.consecutiveFailureCount());
        assertEquals(Boolean.TRUE, endpoint.enabled());
    }

    private static Map<String, Object> linked(Object... kv) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
