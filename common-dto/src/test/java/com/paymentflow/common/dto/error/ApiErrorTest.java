package com.paymentflow.common.dto.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorTest {

    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Test
    void factorySetsTimestampAndEmptyErrors() {
        ApiError error = ApiError.of(404, "NOT_FOUND", "missing", "/x", "corr-1");

        assertThat(error.timestamp()).isNotNull();
        assertThat(error.status()).isEqualTo(404);
        assertThat(error.code()).isEqualTo("NOT_FOUND");
        assertThat(error.errors()).isEmpty();
    }

    @Test
    void errorsListIsDefensivelyCopied() {
        List<ApiFieldError> source = new ArrayList<>();
        source.add(new ApiFieldError("name", "must not be blank"));

        ApiError error = ApiError.of(400, "VALIDATION_FAILED", "invalid", "/x", "corr-1", source);
        source.clear(); // must not affect the record

        assertThat(error.errors()).hasSize(1);
        assertThat(error.errors().get(0).field()).isEqualTo("name");
    }

    @Test
    void serializesStableContractAndOmitsNulls() throws Exception {
        ApiError error = ApiError.of(409, "CONFLICT", "already exists", "/x", null);

        String json = mapper.writeValueAsString(error);

        assertThat(json).contains("\"status\":409").contains("\"code\":\"CONFLICT\"");
        // correlationId is null -> omitted by @JsonInclude(NON_NULL)
        assertThat(json).doesNotContain("correlationId");
    }

    @Test
    void roundTripsThroughJackson() throws Exception {
        ApiError original = ApiError.of(400, "VALIDATION_FAILED", "invalid", "/x", "corr-9",
                List.of(new ApiFieldError("amount", "must be positive")));

        ApiError parsed = mapper.readValue(mapper.writeValueAsString(original), ApiError.class);

        assertThat(parsed.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(parsed.errors()).singleElement()
                .satisfies(fe -> assertThat(fe.field()).isEqualTo("amount"));
    }
}
