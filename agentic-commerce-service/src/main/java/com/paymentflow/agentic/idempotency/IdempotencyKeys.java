package com.paymentflow.agentic.idempotency;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Derives the {@code Idempotency-Key} the platform sees, deterministically, from what the
 * action logically <em>is</em>.
 *
 * <p><b>Why derived and not generated.</b> A random key would make every retry a new payment.
 * The failure mode that matters is not a network blip — it is the model deciding, one turn
 * later, that it had better call {@code complete_checkout} again. A key generated from the
 * conversation, the tool, the resource and the step is the same key both times, so the second
 * call reaches the platform's existing replay record and returns the <em>original</em>
 * response instead of charging again. The agent cannot double-charge because it cannot
 * produce a second key for the same logical action, not because anyone remembered to check.
 *
 * <h2>Canonical serialization</h2>
 *
 * <p>The tuple is serialized with explicit length framing before it is hashed:
 *
 * <pre>
 *   canonical := DOMAIN || frame(conversationId) || frame(toolName) || frame(resourceId) || frame(step)
 *
 *   frame(x)  := 0x00                                  when x is absent
 *              | 0x01 || int32be(len(utf8(x))) || utf8(x)   otherwise
 * </pre>
 *
 * <p><b>This is injective, and that is the whole requirement.</b> Plain concatenation is not:
 * {@code ("ab", "c")} and {@code ("a", "bc")} both flatten to {@code "abc"}, so two different
 * logical actions would share one key and the second would silently replay the first — a
 * refund quietly becoming a no-op is precisely the bug this framing exists to make
 * unrepresentable. With a length written before every component, the byte string can be parsed
 * back into exactly one tuple, so distinct tuples always produce distinct bytes. A separator
 * character would not do: any character chosen as the separator can also appear inside a
 * value. The presence byte keeps a {@code null} component distinct from an empty one for the
 * same reason.
 *
 * <p>{@code DOMAIN} carries a version. If the derivation ever changes, the version changes
 * with it, and keys minted under the old scheme keep meaning what they meant — a hash whose
 * inputs changed under it is worse than no hash at all.
 *
 * @see #forStep for the composite payment path
 * @see #forRefund for the refund path, whose fourth component is the amount rather than a step
 */
public final class IdempotencyKeys {

    /**
     * Domain separation and scheme version. Present so that a key derived by this service can
     * never collide with one derived for some other purpose from the same tuple, and so a
     * future change to the derivation is a visibly different namespace rather than a silent
     * reinterpretation of existing keys.
     */
    private static final byte[] DOMAIN = "paymentflow/agentic/idempotency/v1".getBytes(StandardCharsets.UTF_8);

    /**
     * Prefix on the emitted key. Purely for humans reading a request log or an
     * {@code agent_action_steps} row: it says at a glance that this key was derived by the
     * agent layer rather than typed by a merchant integration.
     */
    private static final String PREFIX = "agt_";

    private static final byte ABSENT = 0x00;
    private static final byte PRESENT = 0x01;

    private IdempotencyKeys() {
    }

    /**
     * The key for one platform operation inside a composite money tool.
     *
     * <p>{@code step} is what separates the create, the authorize and the capture of a single
     * {@code complete_checkout}: three platform calls, three distinct keys, one logical action.
     * Re-running the tool re-derives all three, so each call meets its own replay record and
     * the lifecycle resumes rather than restarting.
     *
     * @param conversationId the conversation the action belongs to
     * @param toolName       the registry's stable tool name
     * @param resourceId     the checkout being paid — the resource whose identity makes this
     *                       action distinct from the same tool run against something else
     * @param step           the operation within the tool, e.g. {@code create}, {@code authorize}
     */
    public static String forStep(UUID conversationId, String toolName, UUID resourceId, String step) {
        return derive(conversationId, toolName, resourceId, step);
    }

    /**
     * The key for a refund, whose fourth component is the amount rather than a step name —
     * exactly as AD-12 specifies.
     *
     * <p><b>The consequence is deliberate and worth stating.</b> Two refunds of the same amount
     * against the same payment in the same conversation derive the same key, so the second is a
     * replay and returns the first refund rather than issuing another. That is the safe
     * direction for the failure it prevents: a model that repeats itself refunds once. A
     * merchant who genuinely wants to refund the same amount twice does it through the API
     * directly, where they choose their own key.
     */
    public static String forRefund(UUID conversationId, String toolName, UUID paymentId, long amountMinor) {
        return derive(conversationId, toolName, paymentId, Long.toString(amountMinor));
    }

    /**
     * The general derivation. Package-visible surface is the two named methods above; this
     * one is public because the tool layer needs to derive keys for operations that are
     * neither of those, and a third named wrapper per tool would be ceremony.
     */
    public static String derive(UUID conversationId, String toolName, UUID resourceId, String discriminator) {
        ByteArrayOutputStream canonical = new ByteArrayOutputStream();
        canonical.writeBytes(DOMAIN);
        frame(canonical, conversationId == null ? null : conversationId.toString());
        frame(canonical, toolName);
        frame(canonical, resourceId == null ? null : resourceId.toString());
        frame(canonical, discriminator);
        return PREFIX + HexFormat.of().formatHex(sha256(canonical.toByteArray()));
    }

    /**
     * Writes one component with a presence byte and a big-endian length, so the byte string
     * can be parsed back into exactly one tuple.
     */
    private static void frame(ByteArrayOutputStream out, String value) {
        if (value == null) {
            out.write(ABSENT);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(PRESENT);
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every Java platform specification since 1.4. If it is
            // genuinely absent the JVM is broken in a way no fallback could paper over, and
            // deriving a weaker key would be far worse than failing loudly.
            throw new IllegalStateException("SHA-256 is unavailable on this JVM.", e);
        }
    }
}
