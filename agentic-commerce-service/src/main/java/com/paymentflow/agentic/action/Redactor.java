package com.paymentflow.agentic.action;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns validated tool arguments into the canonical, redacted summary the action log stores.
 *
 * <p>This class is the reason the action log can be handed to anyone. Two independent
 * defences, because either one alone fails in a way the other catches:
 *
 * <ol>
 *   <li><b>Key-based.</b> Anything whose name looks like a credential is replaced wholesale,
 *       never truncated or partially shown. A prefix of a secret is still a secret.</li>
 *   <li><b>Value-based.</b> Anything whose <em>value</em> looks like one of this platform's
 *       credential formats is replaced regardless of what it is called. This is the defence
 *       that matters, because the threat is a model putting a key in a field named
 *       {@code description}.</li>
 * </ol>
 *
 * <p>Deliberately not configurable. A redaction list that can be turned off is one that will
 * be, and the failure is silent and permanent — a secret written to an append-only log is
 * not recoverable by fixing the configuration afterwards.
 */
public final class Redactor {

    /** What replaces a redacted value. Fixed-width and obvious, so a reader cannot mistake it for data. */
    public static final String REDACTED = "[REDACTED]";

    /** A key containing any of these, case-insensitively, is redacted whatever its value. */
    private static final Set<String> SENSITIVE_KEY_FRAGMENTS = Set.of(
            "secret", "password", "passwd", "token", "credential", "authorization", "auth",
            "apikey", "api_key", "key_secret", "keysecret", "privatekey", "private_key",
            "signature", "bearer", "session", "cookie");

    /**
     * Value shapes that are credentials wherever they appear.
     *
     * <p>The platform's own key formats ({@code sk_}/{@code pk_} with a mode segment),
     * Razorpay's ({@code rzp_test_}/{@code rzp_live_}), this platform's webhook signing
     * secrets ({@code whsec_}), Anthropic's ({@code sk-ant-}), OpenAI's ({@code sk-proj-},
     * {@code sk-svcacct-}, {@code sk-admin-}, and the legacy long {@code sk-} form), and
     * anything presented as an HTTP {@code Basic}/{@code Bearer} credential.
     */
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(sk-ant-[A-Za-z0-9_-]{8,})"
                    + "|(sk-(?:proj|svcacct|admin|None)-[A-Za-z0-9_-]{8,})"
                    + "|(sk-[A-Za-z0-9]{32,})"
                    + "|((?:sk|pk)_(?:test|live)_[A-Za-z0-9_-]{6,})"
                    + "|(rzp_(?:test|live)_[A-Za-z0-9]{6,})"
                    + "|(whsec_[A-Za-z0-9_-]{6,})"
                    + "|((?i:bearer|basic)\\s+[A-Za-z0-9+/=._-]{8,})",
            Pattern.CASE_INSENSITIVE);

    /** A summary is prompt-adjacent and log-bound; neither wants an unbounded string. */
    private static final int MAX_VALUE_LENGTH = 300;

    private Redactor() {
    }

    /**
     * Renders arguments as a stable, sorted, redacted {@code key=value} line.
     *
     * <p>Sorted because the summary is compared across runs in tests and read side by side by
     * humans, and a map iteration order that changes between JVM runs makes both harder for
     * no benefit.
     */
    public static String summarise(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        return new TreeMap<>(arguments).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + redactValue(entry.getKey(), entry.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    /** Redacts a single value, applying both the key rule and the value rule. */
    public static String redactValue(String key, Object value) {
        if (isSensitiveKey(key)) {
            return REDACTED;
        }
        if (value == null) {
            return "null";
        }
        return redactText(String.valueOf(value));
    }

    /**
     * Applies the value rule to free text. Used for anything reaching a log or a prompt that
     * did not arrive as a named argument — a provider error message, for instance, which is
     * written by someone else and could contain anything.
     */
    public static String redactText(String text) {
        if (text == null) {
            return null;
        }
        String redacted = SENSITIVE_VALUE.matcher(text).replaceAll(REDACTED);
        return redacted.length() <= MAX_VALUE_LENGTH
                ? redacted
                : redacted.substring(0, MAX_VALUE_LENGTH - 3) + "...";
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEY_FRAGMENTS.stream().anyMatch(lower::contains);
    }
}
