package com.paymentflow.common.redaction;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrubs secrets out of request and response material before it is stored or serialized
 * (M20.1, §5/M20 task 2).
 *
 * <p><b>Why this runs before serialization, not after.</b> M20's risk table names "a secret
 * leaks into a stored request body" as the failure worth engineering against, and the
 * mitigation it specifies is ordering: redact, then serialize. Scrubbing a JSON string
 * after the fact means the unredacted value already existed as a serialized object that
 * something could have logged, buffered, or shipped. Here the caller hands over the body it
 * captured and receives back the only form that is allowed to travel any further.
 *
 * <p><b>Two independent layers, deliberately.</b> Field-name matching catches
 * {@code {"password": "hunter2"}} — a secret whose <em>value</em> has no recognisable shape.
 * Pattern matching catches {@code {"note": "my key is sk_test_..."}} — a secret in a field
 * nobody would think to list. Either layer alone leaves a whole class of leak intact, so
 * every string value goes through both regardless of where it sits in the tree.
 *
 * <p><b>Failure is closed, never open.</b> A body that cannot be parsed as JSON is not
 * passed through untouched; it falls back to pattern scrubbing over the raw text. A body
 * that is pathologically large skips parsing for cost reasons and takes the same fallback.
 * There is no path through this class that returns input unexamined — that is the property
 * the secret-corpus test exists to hold down.
 */
public final class RequestRedactor {

    /** What every redacted value is replaced with. Fixed, so it is greppable in a stored log. */
    public static final String REDACTED = "[REDACTED]";

    /** Appended when a body is cut to the cap, so a truncated body is never mistaken for a complete one. */
    public static final String TRUNCATION_MARKER = "...[truncated]";

    /**
     * Above this many characters a body is not parsed as JSON at all. Redaction cost on a
     * hostile or accidental multi-megabyte body would otherwise land on the request path
     * this milestone promises never to slow down (D109). Pattern scrubbing is linear and
     * still applies, so the large-body case is cheaper, not laxer.
     */
    private static final int MAX_PARSE_LENGTH = 256 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Field names whose <em>value</em> is always a secret, matched after normalising away
     * case and word separators — so {@code apiKey}, {@code api_key}, {@code API-KEY} and
     * {@code api key} are one entry rather than four.
     *
     * <p>Bare {@code key} is deliberately absent. It is the field name most likely to hold
     * something harmless ({@code Idempotency-Key}, a {@code metadata} entry literally named
     * "key"), and redacting it would destroy legitimate debugging information — which is the
     * one thing a request log exists to provide. The patterns below still catch a real
     * credential that lands in such a field.
     */
    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "password", "newpassword", "currentpassword", "oldpassword", "passwordconfirmation",
            "authorization", "proxyauthorization", "cookie", "setcookie",
            "secret", "clientsecret", "signingsecret", "webhooksecret", "apisecret",
            "token", "accesstoken", "refreshtoken", "idtoken", "bearertoken", "sessiontoken",
            "apikey", "privatekey", "encryptionkey",
            "cardnumber", "pan", "cvv", "cvc", "securitycode");

    /**
     * Header names redacted wholesale. The {@code X-PF-Internal-*} family is here for a
     * reason worth stating: those headers are a signed assertion of merchant identity, so a
     * stored copy of {@code X-PF-Internal-Signature} plus its companions is a replayable
     * credential, not merely sensitive metadata.
     */
    private static final Set<String> SENSITIVE_HEADER_NAMES = Set.of(
            "authorization", "proxyauthorization", "cookie", "setcookie", "xapikey");

    private static final String INTERNAL_HEADER_PREFIX = "x-pf-internal-";

    /**
     * Credential shapes this platform actually issues, rather than a generic guess:
     * {@code {pk|sk}_{test|live}_<24 base62>} (merchant-service's {@code ApiKeySecretGenerator})
     * and {@code whsec_<32 base62>} (notification-service's {@code WebhookSecretGenerator}).
     * The length floor is loose so a truncated or future-length secret is still caught.
     */
    private static final Pattern API_KEY_PATTERN =
            Pattern.compile("\\b(?:pk|sk)_(?:test|live)_[A-Za-z0-9]{8,}");

    private static final Pattern WEBHOOK_SECRET_PATTERN =
            Pattern.compile("\\bwhsec_[A-Za-z0-9]{8,}");

    /** A compact JWS: three base64url segments, the first of which starts a JSON header. */
    private static final Pattern JWT_PATTERN =
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}");

    /**
     * PAN-shaped digit runs, 13–19 long, optionally grouped by spaces or hyphens. Every
     * match is additionally Luhn-checked before being redacted — without that, any
     * sufficiently long number (a microsecond timestamp, an order reference, an amount in
     * minor units) would be destroyed, and a request log that eats legitimate identifiers is
     * a debugging tool nobody trusts. Luhn is what distinguishes "looks like a card" from
     * "is long".
     */
    private static final Pattern PAN_PATTERN =
            Pattern.compile("(?<![0-9])(?:[0-9][ -]?){12,18}[0-9](?![0-9])");

    /**
     * A {@code name=value} pair as it appears in form encoding, a query string, or a cookie.
     * The value runs to the next separator, so {@code &}, {@code ;} and whitespace all end
     * it — which is what keeps one redacted field from swallowing the rest of the body.
     */
    private static final Pattern NAMED_VALUE_PATTERN =
            Pattern.compile("([A-Za-z0-9_.\\-\\[\\]]+)=([^&;\\s]*)");

    private RequestRedactor() {
    }

    /**
     * Redacts a captured body and caps its length.
     *
     * <p>Redaction runs <em>before</em> truncation, never the other way round: cutting first
     * could sever a secret mid-token so that neither half matches a pattern, leaving a
     * recognisable prefix of a live credential in the stored row.
     *
     * @param body      the captured body; {@code null} or blank returns {@code null}
     * @param maxLength the cap applied after redaction, in characters
     * @return the redacted, capped body, or {@code null} if there was nothing to store
     */
    public static String redactBody(String body, int maxLength) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String redacted = body.length() > MAX_PARSE_LENGTH ? redactText(body) : redactJsonOrText(body);
        return truncate(redacted, maxLength);
    }

    /**
     * Redacts a header map, dropping the value of any header whose name is a credential and
     * pattern-scrubbing the rest — a custom header carrying a key in its value is caught by
     * the second half even though its name is on no list.
     */
    public static Map<String, String> redactHeaders(Map<String, List<String>> headers) {
        Map<String, String> redacted = new LinkedHashMap<>();
        if (headers == null || headers.isEmpty()) {
            return redacted;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            String joined = entry.getValue() == null ? "" : String.join(", ", entry.getValue());
            redacted.put(name, isSensitiveHeader(name) ? REDACTED : redactText(joined));
        }
        return redacted;
    }

    /**
     * Scrubs an arbitrary string: {@code name=value} pairs whose name is a credential, then
     * the credential shapes.
     *
     * <p><b>Why the name pass is here and not only on the JSON path.</b> It was originally
     * structural-only, and the corpus sweep caught the hole immediately: a form-encoded
     * {@code password=hunter2} is not JSON, so it took the text fallback, and a password has
     * no recognisable <em>shape</em> for a pattern to match. The value survived. Field-name
     * matching has to work on unstructured input too, otherwise "which layer protects this
     * body?" depends on a content type the caller may not even have set correctly. This also
     * makes the method safe to point at a query string, where the same risk exists.
     */
    public static String redactText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = redactNamedValues(text);
        result = API_KEY_PATTERN.matcher(result).replaceAll(REDACTED);
        result = WEBHOOK_SECRET_PATTERN.matcher(result).replaceAll(REDACTED);
        result = JWT_PATTERN.matcher(result).replaceAll(REDACTED);
        return redactPans(result);
    }

    private static String redactNamedValues(String text) {
        if (text.indexOf('=') < 0) {
            return text;
        }
        Matcher matcher = NAMED_VALUE_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = isSensitiveField(matcher.group(1))
                    ? matcher.group(1) + "=" + REDACTED
                    : matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String redactJsonOrText(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            // A bare scalar is valid JSON ("123", "\"text\"") but carries no field names, so
            // the structural layer has nothing to contribute and the text layer is the whole
            // answer. Falling through keeps the original formatting instead of re-rendering.
            if (!isContainer(root)) {
                return redactText(body);
            }
            redactNode(root);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            // Not JSON (form encoding, XML, a truncated capture, binary). Never returned
            // unexamined — the text layer still runs over it.
            return redactText(body);
        }
    }

    private static void redactNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (String fieldName : List.copyOf(object.propertyNames())) {
                JsonNode value = object.get(fieldName);
                if (isSensitiveField(fieldName)) {
                    // Replaced whatever its type is: a secret smuggled as a number or an
                    // object under a sensitive name is still a secret.
                    object.put(fieldName, REDACTED);
                } else if (isContainer(value)) {
                    redactNode(value);
                } else if (value != null && value.isString()) {
                    object.put(fieldName, redactText(value.stringValue()));
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode element = array.get(i);
                if (isContainer(element)) {
                    redactNode(element);
                } else if (element != null && element.isString()) {
                    array.set(i, MAPPER.getNodeFactory().stringNode(redactText(element.stringValue())));
                }
            }
        }
    }

    /**
     * {@code isObject() || isArray()} rather than Jackson's own container predicate, whose
     * name moved between Jackson 2 and 3 — these two have been stable across both.
     */
    private static boolean isContainer(JsonNode node) {
        return node != null && (node.isObject() || node.isArray());
    }

    private static String redactPans(String text) {
        Matcher matcher = PAN_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String candidate = matcher.group();
            String digits = candidate.replaceAll("[ -]", "");
            matcher.appendReplacement(result, Matcher.quoteReplacement(isLuhnValid(digits) ? REDACTED : candidate));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean isLuhnValid(String digits) {
        int sum = 0;
        boolean doubling = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }

    private static boolean isSensitiveField(String fieldName) {
        return SENSITIVE_FIELD_NAMES.contains(normalize(fieldName));
    }

    private static boolean isSensitiveHeader(String headerName) {
        if (headerName == null) {
            return false;
        }
        return SENSITIVE_HEADER_NAMES.contains(normalize(headerName))
                || headerName.toLowerCase(Locale.ROOT).startsWith(INTERNAL_HEADER_PREFIX);
    }

    /** Lower-cases and strips the separators that distinguish spellings but not meaning. */
    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != '_' && c != '-' && c != ' ' && c != '.') {
                builder.append(Character.toLowerCase(c));
            }
        }
        return builder.toString();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + TRUNCATION_MARKER;
    }
}
