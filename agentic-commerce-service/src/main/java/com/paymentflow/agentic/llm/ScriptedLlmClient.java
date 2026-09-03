package com.paymentflow.agentic.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic {@link LlmClient} that plays scripted scenarios instead of calling a model.
 *
 * <p><b>It has two jobs, and they are the same job.</b> In CI it drives the entire agent
 * pipeline — proposal, validation, policy, approval, execution, action trail — with no
 * credential and no network, so every guarantee the runtime makes is tested rather than
 * asserted. In a deployment with no {@code ANTHROPIC_API_KEY} it is what the service falls back
 * to, so the demo runs and fails honestly rather than erroring at startup.
 *
 * <h2>Stateless by construction</h2>
 *
 * <p>The client holds no per-conversation state. Which step of a scenario to play is derived
 * from the request itself — the assistant turns already in the transcript are the step index —
 * so the same request always produces the same response, and two conversations cannot interfere.
 * That is what makes it deterministic in the sense the tests need: not merely repeatable, but
 * independent of call order and of anything that happened in another test.
 *
 * <h2>What it is not</h2>
 *
 * <p>It is not a model, and it does not try to be. A scenario is a fixed sequence of tool calls
 * chosen by a keyword in the user's message. It cannot reason, and it deliberately cannot do
 * anything the real client could not: it produces {@link LlmToolCall}s that go through exactly
 * the same registry, policy and approval path, and it has no privileged access to any of them.
 */
public class ScriptedLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ScriptedLlmClient.class);

    /** The model name recorded on actions taken under a script, so the trail never claims a real model ran. */
    public static final String SCRIPTED_MODEL = "scripted";

    /**
     * The instrument the built-in purchase scenario uses.
     *
     * <p>One of the platform's own published test tokens (GET /v1/test/cards), not an invented
     * one — because {@code InstrumentAllowList} checks every instrument against that catalogue
     * and refuses anything absent from it. An earlier draft used a made-up token here and the
     * allow-list correctly rejected it, which was the control working exactly as designed and a
     * useful reminder that this client has no privileges the real one lacks.
     *
     * <p>In a real deployment the instrument is chosen in the UI and passed as data; this
     * constant exists only so the credential-free demo can complete a purchase.
     */
    private static final String DEMO_INSTRUMENT = "pm_card_visa";

    private static final Pattern UUID_IN_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final ObjectMapper objectMapper;
    private final List<Scenario> scenarios = new ArrayList<>();

    public ScriptedLlmClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        registerBuiltInScenarios();
    }

    // ── The scripting surface ───────────────────────────────────────────────────────────

    /** One scripted behaviour: when it applies, and what it does on each successive turn. */
    public record Scenario(String name, Predicate<String> matches, Turn turn) {
    }

    /**
     * What the script does on one turn.
     *
     * <p>Free to throw — {@link LlmUnavailableException} and {@link MalformedLlmOutputException}
     * are both legitimate scripted outcomes, and being able to script them is how the runtime's
     * handling of a broken provider gets tested at all.
     */
    @FunctionalInterface
    public interface Turn {
        LlmResponse respond(ScriptedContext context);
    }

    /**
     * What a scripted turn can see: how many turns have happened, and the structured results of
     * every tool call so far.
     *
     * <p>Results are exposed parsed rather than as raw JSON so a scenario can chain — search for
     * a product, then create a checkout with the id the search returned — without doing string
     * surgery on a payload.
     */
    public final class ScriptedContext {

        private final LlmRequest request;
        private final int turnIndex;

        private ScriptedContext(LlmRequest request, int turnIndex) {
            this.request = request;
            this.turnIndex = turnIndex;
        }

        public LlmRequest request() {
            return request;
        }

        /** Zero on the first response of a scenario, one on the next, and so on. */
        public int turnIndex() {
            return turnIndex;
        }

        public String userText() {
            return request.lastUserText() == null ? "" : request.lastUserText();
        }

        /** The first UUID appearing in the user's message, for scenarios that act on a named object. */
        public Optional<String> uuidInUserText() {
            Matcher matcher = UUID_IN_TEXT.matcher(userText());
            return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
        }

        /** A top-level field from the most recent successful result of one tool. */
        public Optional<String> resultField(String toolName, String field) {
            return lastPayload(toolName).map(payload -> payload.get(field)).map(String::valueOf);
        }

        /** The id of the first product in the most recent {@code search_products} result. */
        public Optional<String> firstProductId() {
            return lastPayload("search_products")
                    .map(payload -> payload.get("products"))
                    .filter(List.class::isInstance)
                    .map(List.class::cast)
                    .filter(products -> !products.isEmpty())
                    .map(products -> products.getFirst())
                    .filter(Map.class::isInstance)
                    .map(product -> String.valueOf(((Map<?, ?>) product).get("id")));
        }

        private Optional<Map<String, Object>> lastPayload(String toolName) {
            for (int i = request.messages().size() - 1; i >= 0; i--) {
                LlmMessage message = request.messages().get(i);
                for (LlmToolResult result : message.toolResults()) {
                    if (result.toolName().equals(toolName) && !result.error()) {
                        return parse(result.content());
                    }
                }
            }
            return Optional.empty();
        }

        @SuppressWarnings("unchecked")
        private Optional<Map<String, Object>> parse(String content) {
            if (content == null || content.isBlank()) {
                return Optional.empty();
            }
            try {
                Object parsed = objectMapper.readValue(content, Object.class);
                if (parsed instanceof Map<?, ?> map) {
                    Object payload = map.get("payload");
                    return Optional.of((Map<String, Object>) (payload instanceof Map ? payload : map));
                }
            } catch (RuntimeException e) {
                log.debug("A scripted scenario could not read a tool result payload.", e);
            }
            return Optional.empty();
        }
    }

    /**
     * Adds a scenario, ahead of the built-in ones.
     *
     * <p>Registered first-match-wins in reverse registration order, so a test can override a
     * built-in behaviour for one keyword without having to remove it.
     */
    public void register(Scenario scenario) {
        scenarios.addFirst(scenario);
    }

    /** Registers a scenario matching any user message containing {@code trigger}, case-insensitively. */
    public void register(String name, String trigger, Turn turn) {
        String needle = trigger.toLowerCase(Locale.ROOT);
        register(new Scenario(name, text -> text.contains(needle), turn));
    }

    // ── LlmClient ───────────────────────────────────────────────────────────────────────

    @Override
    public String providerName() {
        return "scripted";
    }

    /** Always. That is the point of it — a pipeline that can be exercised with no credential. */
    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String userText = request.lastUserText() == null
                ? "" : request.lastUserText().toLowerCase(Locale.ROOT);
        ScriptedContext context = new ScriptedContext(request, request.assistantTurnCount());

        return scenarios.stream()
                .filter(scenario -> scenario.matches().test(userText))
                .findFirst()
                .map(scenario -> scenario.turn().respond(context))
                .orElseGet(() -> LlmResponse.text(
                        "I can search the catalogue, build a checkout and take a payment. "
                                + "Tell me what you are looking for.", SCRIPTED_MODEL));
    }

    // ── Built-in scenarios ──────────────────────────────────────────────────────────────

    /**
     * Enough behaviour for the demo to work without a credential: find something, buy it, and
     * answer questions about what happened.
     *
     * <p>Registered in reverse priority — later registrations are matched first — so the more
     * specific triggers sit in front of the general ones.
     */
    private void registerBuiltInScenarios() {
        register("explain", "explain", onFirstTurn(context -> context.uuidInUserText()
                .map(paymentId -> toolCall("explain_payment_outcome",
                        Map.of("paymentId", paymentId), context))
                .orElseGet(() -> LlmResponse.text(
                        "Tell me which payment to explain and I will look it up.", SCRIPTED_MODEL))));

        register("status", "status", onFirstTurn(context -> context.uuidInUserText()
                .map(paymentId -> toolCall("get_payment_status",
                        Map.of("paymentId", paymentId), context))
                .orElseGet(() -> LlmResponse.text(
                        "Tell me which payment to check and I will look it up.", SCRIPTED_MODEL))));

        register("refund", "refund", onFirstTurn(context -> context.uuidInUserText()
                .map(paymentId -> toolCall("request_refund", Map.of("paymentId", paymentId), context))
                .orElseGet(() -> LlmResponse.text(
                        "Tell me which payment to refund and I will request it.", SCRIPTED_MODEL))));

        // The full purchase path: find it, quote it, pay for it. Three tool turns, then a reply
        // assembled from what the tools actually returned.
        register(new Scenario("buy", text -> text.contains("buy") || text.contains("purchase")
                || text.contains("order"), this::buyTurn));

        register(new Scenario("search", text -> text.contains("search") || text.contains("show")
                || text.contains("looking for") || text.contains("sell"),
                context -> context.turnIndex() == 0
                        ? toolCall("search_products", Map.of("query", searchTerm(context)), context)
                        : LlmResponse.text("Here is what I found in the catalogue.", SCRIPTED_MODEL)));
    }

    private LlmResponse buyTurn(ScriptedContext context) {
        return switch (context.turnIndex()) {
            case 0 -> toolCall("search_products", Map.of("query", searchTerm(context)), context);
            case 1 -> context.firstProductId()
                    .map(productId -> toolCall("create_checkout",
                            Map.of("items", List.of(Map.of("productId", productId, "quantity", 1))),
                            context))
                    .orElseGet(() -> LlmResponse.text(
                            "I could not find that in the catalogue.", SCRIPTED_MODEL));
            case 2 -> context.resultField("create_checkout", "id")
                    .map(checkoutId -> toolCall("complete_checkout",
                            Map.of("checkoutId", checkoutId, "instrumentToken", DEMO_INSTRUMENT),
                            context))
                    .orElseGet(() -> LlmResponse.text(
                            "I could not build a checkout for that.", SCRIPTED_MODEL));
            default -> LlmResponse.text(
                    "That is everything I can do for this order.", SCRIPTED_MODEL);
        };
    }

    /** Everything after the trigger word, or a blank query — which lists the catalogue. */
    private static String searchTerm(ScriptedContext context) {
        String text = context.userText();
        for (String trigger : List.of("buy", "purchase", "order", "search for", "search",
                "looking for", "show me")) {
            int index = text.toLowerCase(Locale.ROOT).indexOf(trigger);
            if (index >= 0) {
                return text.substring(index + trigger.length()).trim();
            }
        }
        return "";
    }

    /**
     * Wraps a one-shot turn so it fires once and then reports what it did.
     *
     * <p>Without this, a scenario that returns a tool call unconditionally asks for one again on
     * every iteration and runs the loop straight into its ceiling — which is a bug in the script,
     * not in the runtime, but an easy one to mistake for the latter. Most scenarios genuinely are
     * one-shot, so this is the shape they should have by default.
     */
    public static Turn onFirstTurn(Turn first) {
        return context -> context.turnIndex() == 0
                ? first.respond(context)
                : LlmResponse.text("Here is what I found.", SCRIPTED_MODEL);
    }

    /**
     * Builds a tool call with a deterministic id.
     *
     * <p>Derived from the scenario's position rather than randomly generated, so two runs of the
     * same test produce byte-identical transcripts — which is what lets a transcript be asserted
     * on at all.
     */
    public static LlmResponse toolCall(String toolName, Map<String, Object> arguments,
                                       ScriptedContext context) {
        String id = "scripted_%s_%d".formatted(toolName, context.turnIndex());
        return LlmResponse.toolUse(null,
                List.of(new LlmToolCall(id, toolName, new LinkedHashMap<>(arguments))),
                SCRIPTED_MODEL);
    }

    /** A tool call outside any scenario context, for tests that script one call directly. */
    public static LlmResponse singleToolCall(String callId, String toolName, Map<String, Object> arguments) {
        return LlmResponse.toolUse(null,
                List.of(new LlmToolCall(callId, toolName, new LinkedHashMap<>(arguments))),
                SCRIPTED_MODEL);
    }
}
