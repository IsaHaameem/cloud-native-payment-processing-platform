package com.paymentflow.agentic.platform;

import com.paymentflow.common.dto.error.ErrorType;
import com.paymentflow.common.error.ErrorCode;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The platform's own error code, passed through rather than reinterpreted.
 *
 * <p>AD-12 is explicit about this: when a payment declines, "the tool returns it verbatim and
 * the agent explains <b>using that code</b>". A decline that the platform called
 * {@code card_declined} must not become "something went wrong" on the way to the buyer, and it
 * must not be re-mapped onto one of this service's own codes either — re-mapping is how
 * {@code insufficient_funds} and {@code card_expired} end up indistinguishable, which is
 * exactly the difference a buyer needs in order to do something about it.
 *
 * <p>{@link ErrorCode} being an interface is what makes this possible; its javadoc anticipates
 * services declaring their own implementations rather than forcing every code into one shared
 * enum.
 *
 * <p><b>The code is sanitised on the way in.</b> It arrives over the network, and it is written
 * into logs and into the model's context. Anything that is not a plain uppercase identifier is
 * replaced wholesale rather than escaped — a code is a closed vocabulary, and something outside
 * that vocabulary is not a code this service should be repeating.
 */
public record PlatformErrorCode(String code, int httpStatus, ErrorType type, String defaultMessage)
        implements ErrorCode {

    /** What the platform's codes look like: {@code PAYMENT_NOT_CAPTURABLE}, {@code NOT_FOUND}. */
    private static final Pattern WELL_FORMED = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    private static final String UNRECOGNISED = "PLATFORM_ERROR";

    public PlatformErrorCode {
        code = sanitise(code);
        type = type == null ? classify(httpStatus) : type;
        defaultMessage = defaultMessage == null ? "The payment platform rejected this request." : defaultMessage;
    }

    /** Builds a code from what an {@code ApiError} body actually contained. */
    public static PlatformErrorCode of(String code, int httpStatus, String message) {
        return new PlatformErrorCode(code, httpStatus, classify(httpStatus), message);
    }

    /**
     * Classification by status, because {@code ApiError.type} is advisory and this service
     * must behave sensibly even if it is absent. The mapping is the platform's own: a 4xx is a
     * verdict about the request, a 5xx is a statement about the platform.
     */
    private static ErrorType classify(int httpStatus) {
        return switch (httpStatus) {
            case 401 -> ErrorType.AUTHENTICATION_ERROR;
            case 403 -> ErrorType.PERMISSION_ERROR;
            case 429 -> ErrorType.RATE_LIMIT_ERROR;
            case 400, 404, 409, 422 -> ErrorType.INVALID_REQUEST_ERROR;
            default -> ErrorType.API_ERROR;
        };
    }

    private static String sanitise(String code) {
        if (code == null || code.isBlank() || !WELL_FORMED.matcher(code).matches()) {
            return UNRECOGNISED;
        }
        return code.toUpperCase(Locale.ROOT);
    }
}
