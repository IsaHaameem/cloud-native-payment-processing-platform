package com.paymentflow.agentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every tunable this service has, in one place, bound from {@code paymentflow.agentic.*}.
 *
 * <p><b>Why one nested record rather than six flat property classes.</b> Two of these groups
 * hold credentials and one holds financial thresholds. Keeping them together makes the
 * complete list of "things this service must be told before it can move money" readable in a
 * single file, which is what a reviewer asking "where do the secrets live?" actually needs.
 *
 * <p>Nothing here has a hard-coded business threshold. project_3_context.md §42.2 (Q16) left
 * the policy numbers open, so they are configuration by decision rather than by omission —
 * see {@link Policy}. The two credential fields default to values that are either blank or
 * obviously fake, and both failure modes are handled by refusing to act rather than by
 * acting with a wrong value.
 */
@ConfigurationProperties(prefix = "paymentflow.agentic")
public record AgenticProperties(
        Platform platform,
        Policy policy,
        Checkout checkout,
        Llm llm,
        Razorpay razorpay,
        Demo demo) {

    /**
     * How this service reaches the payment platform: the gateway, with one merchant API key.
     *
     * <p>{@code apiKey} is deliberately blank by default. A blank key is detected at the
     * point a money action is about to be attempted and reported as a configuration failure
     * naming the missing property — never as an authentication failure at the gateway, which
     * would send an operator looking in the wrong service.
     */
    public record Platform(String baseUri, String apiKey, int connectTimeoutMs, int readTimeoutMs) {

        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /**
     * The financial bounds the policy engine enforces. All amounts are in the currency's
     * minor unit, matching the platform's absolute rule that money is never a decimal.
     *
     * <p>{@code version} is recorded on every persisted decision, so a decision made under an
     * older set of thresholds stays interpretable after the thresholds change. Without it,
     * the audit trail would say what was decided but not what the rules were at the time,
     * which is the half that matters when someone asks why.
     */
    public record Policy(
            String version,
            String currency,
            long maxPaymentAmountMinor,
            long maxConversationSpendMinor,
            long refundApprovalThresholdMinor,
            long maxRefundAmountMinor,
            long maxConversationRefundMinor,
            int maxToolCallsPerConversation,
            int approvalTtlMinutes) {
    }

    /** How long a quote stands, and how large it may get. */
    public record Checkout(int ttlMinutes, int maxLineItems) {
    }

    /**
     * The model behind the agent. {@code provider} selects an {@code LlmClient} implementation
     * ({@code anthropic} or {@code openai}); a blank credential for the selected provider selects
     * the deterministic scripted client regardless of provider, which is what lets CI exercise
     * the whole agent pipeline without a credential.
     *
     * <p><b>Anthropic stays the default and its configuration is unchanged.</b> {@code apiKey}
     * and {@code model} are the Anthropic credential and model, sourced from {@code ANTHROPIC_API_KEY}
     * and {@code AGENTIC_LLM_MODEL} exactly as before. OpenAI is opt-in: set
     * {@code AGENTIC_LLM_PROVIDER=openai} and {@code OPENAI_API_KEY}, and optionally
     * {@code OPENAI_MODEL}. The two OpenAI fields are separate rather than overloading the
     * Anthropic ones so a machine can carry both credentials and switch provider with one
     * variable — and so "which key belongs to which provider" is never ambiguous.
     *
     * <p>{@link #activeApiKey()}, {@link #activeModel()} and {@link #activeBaseUri()} resolve the
     * right value for {@code provider}; everything downstream of {@code LlmClientConfig} reads
     * those and never the provider-specific fields directly.
     */
    public record Llm(
            String provider,
            String baseUri,
            String apiKey,
            String model,
            int maxTokens,
            double temperature,
            int timeoutMs,
            int maxToolIterations,
            int maxTurnDurationMs,
            String openaiApiKey,
            String openaiModel) {

        private static final String PROVIDER_OPENAI = "openai";
        private static final String DEFAULT_ANTHROPIC_BASE_URI = "https://api.anthropic.com";
        private static final String DEFAULT_OPENAI_BASE_URI = "https://api.openai.com";
        private static final String DEFAULT_OPENAI_MODEL = "gpt-4.1";

        /** Whether {@code provider} names the OpenAI adapter. Case-insensitive; anything else is not OpenAI. */
        public boolean isOpenAi() {
            return PROVIDER_OPENAI.equalsIgnoreCase(provider);
        }

        /**
         * The credential for the selected provider — the OpenAI key when {@code provider=openai},
         * the Anthropic key otherwise. This is the value {@link #isConfigured()} checks and the
         * only one an adapter sends.
         */
        public String activeApiKey() {
            return isOpenAi() ? openaiApiKey : apiKey;
        }

        /**
         * The model id for the selected provider. For OpenAI, {@code OPENAI_MODEL} wins; when it
         * is unset the shared {@code AGENTIC_LLM_MODEL} is used if it names an OpenAI model, and
         * failing that a sensible default — never the Anthropic default, which OpenAI would 404.
         */
        public String activeModel() {
            if (!isOpenAi()) {
                return model;
            }
            if (openaiModel != null && !openaiModel.isBlank()) {
                return openaiModel;
            }
            if (model != null && !model.isBlank() && !model.toLowerCase(java.util.Locale.ROOT).startsWith("claude")) {
                return model;
            }
            return DEFAULT_OPENAI_MODEL;
        }

        /**
         * The base URI for the selected provider. An explicit {@code AGENTIC_LLM_BASE_URI} always
         * wins (it is how a test or a proxy points the adapter somewhere else); otherwise the
         * default follows the provider.
         */
        public String activeBaseUri() {
            if (baseUri != null && !baseUri.isBlank()) {
                return baseUri;
            }
            return isOpenAi() ? DEFAULT_OPENAI_BASE_URI : DEFAULT_ANTHROPIC_BASE_URI;
        }

        public boolean isConfigured() {
            String key = activeApiKey();
            return key != null && !key.isBlank();
        }

        /**
         * The wall-clock ceiling on one agent turn, across every model call and tool execution
         * within it.
         *
         * <p>Added in Phase 11 because {@code maxToolIterations} alone does not bound a turn:
         * eight iterations each waiting out a 30-second read timeout is four minutes of a held
         * request thread, which is an unbounded loop by any measure that matters to an
         * operator. This is the bound that is actually in seconds.
         *
         * <p>A non-positive value disables the check rather than making every turn expire
         * instantly — the opposite of the fail-closed convention the policy thresholds use, and
         * deliberately so: this limit protects a thread, not a budget, and a misconfiguration
         * that stopped the agent answering at all would be a worse failure than one that let a
         * turn run long.
         */
        public boolean hasTurnDeadline() {
            return maxTurnDurationMs > 0;
        }
    }

    /**
     * Razorpay test-mode credentials and behaviour. <b>These exist in this service and
     * nowhere else in the repository.</b>
     *
     * <p>{@code uncollectedOrderOutcome} is the one setting a reader must understand before
     * trusting a demo: see {@code RazorpayPaymentProvider} for the verified Razorpay fact
     * that makes it necessary.
     */
    public record Razorpay(
            boolean enabled,
            String baseUri,
            String keyId,
            String keySecret,
            int connectTimeoutMs,
            int readTimeoutMs,
            String uncollectedOrderOutcome) {

        /** The obviously-fake local defaults, which must never be mistaken for a usable credential. */
        private static final String PLACEHOLDER_KEY_ID = "rzp_test_dev-only-not-a-real-key";
        private static final String PLACEHOLDER_SECRET = "dev-only-insecure-razorpay-secret-change-me";

        /**
         * Whether real credentials are present. Checked before any outbound call so an
         * unconfigured provider reports itself unavailable rather than sending a request that
         * would fail at Razorpay with a 401 — the difference matters because the second form
         * puts a credential on the wire and into somebody's proxy log.
         */
        public boolean isConfigured() {
            return enabled
                    && keyId != null && !keyId.isBlank() && !PLACEHOLDER_KEY_ID.equals(keyId)
                    && keySecret != null && !keySecret.isBlank() && !PLACEHOLDER_SECRET.equals(keySecret);
        }

        /** Whether an order accepted by Razorpay, with no cardholder payment collected, counts as an authorization. */
        public boolean treatsUncollectedOrderAsApproved() {
            return "approve".equalsIgnoreCase(uncollectedOrderOutcome);
        }
    }

    /** Optional demo-catalogue seeding. Inert unless a merchant id is supplied. */
    public record Demo(String merchantId, boolean seedCatalog) {

        public boolean isSeedingEnabled() {
            return seedCatalog && merchantId != null && !merchantId.isBlank();
        }
    }
}
