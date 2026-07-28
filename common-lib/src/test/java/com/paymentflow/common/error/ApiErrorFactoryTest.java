package com.paymentflow.common.error;

import com.paymentflow.common.dto.error.ApiError;
import com.paymentflow.common.dto.error.ApiFieldError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single assembly point for error responses (M21.4).
 *
 * <p>What is worth asserting here is not that the fields are copied — it is the three
 * places the factory makes a judgement the call sites used to make inconsistently or not at
 * all: deriving {@code type} and {@code docUrl} from the code, falling back to the code's
 * default message, and filling {@code param} only when it has an unambiguous answer.
 */
class ApiErrorFactoryTest {

    @Test
    void theTypeAndDocUrlComeFromTheCodeRatherThanFromTheCaller() {
        ApiError error = ApiErrorFactory.create(CommonErrorCode.INSUFFICIENT_SCOPE,
                "This API key does not have the required scope: payments:write",
                "/v1/payments", "req_1", "corr_1");

        // §5/M21's completion criterion — every error response carries a catalogued code —
        // is only true by construction if the caller cannot supply these.
        assertThat(error.type()).isEqualTo("permission_error");
        assertThat(error.code()).isEqualTo("INSUFFICIENT_SCOPE");
        assertThat(error.status()).isEqualTo(403);
        assertThat(error.docUrl()).isEqualTo("https://docs.paymentflow.dev/errors#insufficient_scope");
        assertThat(error.requestId()).isEqualTo("req_1");
        assertThat(error.correlationId()).isEqualTo("corr_1");
    }

    @Test
    void aBlankMessageFallsBackToTheCodesDefault() {
        // The gateway's ResponseStatusException path passes `rse.getReason()`, which is null
        // more often than not. Before this fallback that produced an error body with a null
        // message — technically valid, and useless to whoever received it.
        assertThat(ApiErrorFactory.create(CommonErrorCode.BAD_REQUEST, null, "/v1/payments", "r", "c").message())
                .isEqualTo(CommonErrorCode.BAD_REQUEST.defaultMessage());
        assertThat(ApiErrorFactory.create(CommonErrorCode.BAD_REQUEST, "   ", "/v1/payments", "r", "c").message())
                .isEqualTo(CommonErrorCode.BAD_REQUEST.defaultMessage());
    }

    @Test
    void aCallerSuppliedMessageWins() {
        assertThat(ApiErrorFactory.create(CommonErrorCode.NOT_FOUND, "No such payment.", "/v1/payments/x", "r", "c")
                .message()).isEqualTo("No such payment.");
    }

    @Test
    void paramNamesTheFieldWhenExactlyOneFailed() {
        ApiError error = ApiErrorFactory.forFieldErrors(CommonErrorCode.VALIDATION_FAILED, null,
                "/v1/payments", "r", "c", List.of(new ApiFieldError("amountMinor", "must be positive")));

        assertThat(error.param()).isEqualTo("amountMinor");
        assertThat(error.errors()).hasSize(1);
    }

    @Test
    void paramIsAbsentWhenSeveralFieldsFailed() {
        // Picking the first of several would be a plausible-looking answer to a question
        // with no single answer, and an SDK surfacing it would tell a developer to fix one
        // field while two others were also wrong. `errors` remains the complete answer.
        ApiError error = ApiErrorFactory.forFieldErrors(CommonErrorCode.VALIDATION_FAILED, null,
                "/v1/payments", "r", "c", List.of(
                        new ApiFieldError("amountMinor", "must be positive"),
                        new ApiFieldError("currency", "must be a 3-letter code")));

        assertThat(error.param()).isNull();
        assertThat(error.errors()).hasSize(2);
    }

    @Test
    void paramIsAbsentWhenNoFieldFailed() {
        assertThat(ApiErrorFactory.forFieldErrors(CommonErrorCode.VALIDATION_FAILED, null,
                "/v1/payments", "r", "c", List.of()).param()).isNull();
    }

    @Test
    void everyPublishedCodeProducesACompleteEnvelope() {
        // The criterion applied to the whole catalogue rather than to one example: a code
        // added later without a type would not compile, but one added with a status the
        // factory mishandles would only show up here.
        for (ErrorCode code : ErrorCatalogue.published()) {
            ApiError error = ApiErrorFactory.create(code, null, "/v1/payments", "req_1", "corr_1");

            assertThat(error.type()).describedAs("%s has no type", code.code()).isNotBlank();
            assertThat(error.code()).isEqualTo(code.code());
            assertThat(error.message()).describedAs("%s has no message", code.code()).isNotBlank();
            assertThat(error.docUrl()).describedAs("%s has no docUrl", code.code()).isNotBlank();
            assertThat(error.requestId()).isNotBlank();
            assertThat(error.status()).isBetween(400, 599);
        }
    }
}
