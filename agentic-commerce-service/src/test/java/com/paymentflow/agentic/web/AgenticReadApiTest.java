package com.paymentflow.agentic.web;

import com.paymentflow.agentic.action.AgentAction;
import com.paymentflow.agentic.action.AgentActionJournal;
import com.paymentflow.agentic.action.AgentActionRepository;
import com.paymentflow.agentic.approval.ApprovalRepository;
import com.paymentflow.agentic.catalog.CatalogService;
import com.paymentflow.agentic.catalog.ProductView;
import com.paymentflow.agentic.checkout.CheckoutRepository;
import com.paymentflow.agentic.checkout.CheckoutService;
import com.paymentflow.agentic.config.AgenticProperties;
import com.paymentflow.agentic.conversation.ConversationRepository;
import com.paymentflow.agentic.policy.PolicyCatalog;
import com.paymentflow.agentic.tool.ToolRegistry;
import com.paymentflow.common.exception.ResourceNotFoundException;
import com.paymentflow.common.security.InternalContextFilter;
import com.paymentflow.common.security.InternalContextHeaders;
import com.paymentflow.common.security.InternalContextProperties;
import com.paymentflow.common.security.InternalContextSigner;
import com.paymentflow.common.security.InternalPrincipal;
import com.paymentflow.common.security.MerchantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The read APIs added for G-1 / G-2 / G-3 / G-4, assembled standalone (the pattern the rest of
 * this module's web tests use — no Spring context, no database) with the real
 * {@link InternalContextFilter} in front so the authentication boundary is exercised for real.
 *
 * <p>Every endpoint is checked for: it answers with a verified session context, it refuses an
 * unsigned one with 401, and it scopes its repository call to the context's own {@code (merchant,
 * mode)} — the argument-capture assertions are the unit-level merchant/mode isolation check;
 * cross-tenant isolation with two real merchants is exercised end-to-end against the live stack.
 */
class AgenticReadApiTest {

    private static final String SECRET = "test-only-internal-context-secret";
    private static final UUID MERCHANT = UUID.fromString("889e759a-23ea-4681-820c-396633218d3e");
    private static final UUID USER = UUID.fromString("21a17a69-9bba-4943-937d-1c4884e5a6a1");

    private final InternalContextSigner signer = new InternalContextSigner();

    private final CatalogService catalogService = mock(CatalogService.class);
    private final CheckoutService checkoutService = mock(CheckoutService.class);
    private final CheckoutRepository checkoutRepository = mock(CheckoutRepository.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final AgentActionRepository actionRepository = mock(AgentActionRepository.class);
    private final AgentActionJournal journal = mock(AgentActionJournal.class);
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
    private final com.paymentflow.agentic.provider.ProviderDecisionRepository providerDecisions =
            mock(com.paymentflow.agentic.provider.ProviderDecisionRepository.class);

    private final AgenticProperties properties = AgenticFixtures.properties(MERCHANT.toString());
    private final AgenticCallerContext caller = new AgenticCallerContext(properties);
    private final PolicyCatalog policyCatalog = new PolicyCatalog(properties);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InternalContextFilter filter = new InternalContextFilter(
                new InternalContextProperties(SECRET, 30), signer, new ObjectMapper());
        this.mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new CatalogController(catalogService, caller),
                        new CheckoutController(checkoutService, checkoutRepository, caller),
                        new AgentController(mock(com.paymentflow.agentic.runtime.AgentRuntime.class),
                                mock(com.paymentflow.agentic.conversation.ConversationService.class),
                                conversationRepository, journal, caller),
                        new ActionController(actionRepository, journal, caller),
                        new ConfigController(properties, toolRegistry, policyCatalog, caller),
                        new SummaryController(
                                new SummaryService(conversationRepository, actionRepository,
                                        mock(ApprovalRepository.class)),
                                caller),
                        new ProviderDecisionQueryController(providerDecisions, caller))
                .addFilters(filter)
                .setControllerAdvice(new com.paymentflow.common.web.GlobalExceptionHandler())
                .build();

        when(toolRegistry.specs()).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        MerchantContextHolder.clear();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder builder) {
        long issuedAt = Instant.now().getEpochSecond();
        String sig = signer.sign(SECRET, MERCHANT.toString(), "test", InternalPrincipal.SESSION,
                USER.toString(), null, "*", null, null, issuedAt);
        return builder
                .header(InternalContextHeaders.MERCHANT_ID, MERCHANT.toString())
                .header(InternalContextHeaders.MODE, "test")
                .header(InternalContextHeaders.SCOPES, "*")
                .header(InternalContextHeaders.PRINCIPAL, "session")
                .header(InternalContextHeaders.USER_ID, USER.toString())
                .header(InternalContextHeaders.ISSUED_AT, Long.toString(issuedAt))
                .header(InternalContextHeaders.SIGNATURE, sig);
    }

    private MockHttpServletRequestBuilder forged(MockHttpServletRequestBuilder builder) {
        long issuedAt = Instant.now().getEpochSecond();
        return builder
                .header(InternalContextHeaders.MERCHANT_ID, MERCHANT.toString())
                .header(InternalContextHeaders.MODE, "test")
                .header(InternalContextHeaders.SCOPES, "*")
                .header(InternalContextHeaders.PRINCIPAL, "session")
                .header(InternalContextHeaders.USER_ID, USER.toString())
                .header(InternalContextHeaders.ISSUED_AT, Long.toString(issuedAt))
                .header(InternalContextHeaders.SIGNATURE, "0".repeat(64));
    }

    // ── G-2 catalog ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/agentic/catalog/products")
    class Catalog {

        @Test
        @DisplayName("lists a page, scoped to the context's merchant and mode")
        void listsScoped() throws Exception {
            when(catalogService.listActive(eq(MERCHANT), eq("test"), any(), eq(0), eq(50)))
                    .thenReturn(new CatalogService.Page(List.of(), 0));

            mockMvc.perform(signed(get("/api/agentic/catalog/products")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.limit").value(50))
                    .andExpect(jsonPath("$.total").value(0))
                    .andExpect(jsonPath("$.hasMore").value(false));

            verify(catalogService).listActive(eq(MERCHANT), eq("test"), any(), eq(0), eq(50));
        }

        @Test
        @DisplayName("an unsigned request is refused with 401")
        void unsignedRefused() throws Exception {
            mockMvc.perform(get("/api/agentic/catalog/products")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a forged signature is refused with 401")
        void forgedRefused() throws Exception {
            mockMvc.perform(forged(get("/api/agentic/catalog/products")))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a free-text query uses the capped search, no paging")
        void textQuery() throws Exception {
            when(catalogService.search(eq(MERCHANT), eq("test"), eq("grinder"), any(), eq(50)))
                    .thenReturn(List.of());
            mockMvc.perform(signed(get("/api/agentic/catalog/products").param("query", "grinder")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasMore").value(false));
            verify(catalogService).search(eq(MERCHANT), eq("test"), eq("grinder"), any(), eq(50));
        }

        @Test
        @DisplayName("a missing product is 404, scoped to this merchant")
        void notFound() throws Exception {
            UUID id = UUID.randomUUID();
            when(catalogService.get(eq(MERCHANT), eq("test"), eq(id)))
                    .thenThrow(ResourceNotFoundException.of("Product", id));
            mockMvc.perform(signed(get("/api/agentic/catalog/products/" + id)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("limit is clamped to at most 100")
        void limitClamped() throws Exception {
            when(catalogService.listActive(eq(MERCHANT), eq("test"), any(), eq(0), eq(100)))
                    .thenReturn(new CatalogService.Page(List.of(), 0));
            mockMvc.perform(signed(get("/api/agentic/catalog/products").param("limit", "9999")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.limit").value(100));
        }
    }

    // ── G-2 checkouts ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/agentic/checkouts")
    class Checkouts {

        @Test
        @DisplayName("empty state: an empty page with total 0")
        void emptyState() throws Exception {
            when(checkoutRepository.findByMerchantIdAndModeOrderByCreatedAtDesc(eq(MERCHANT), eq("test"), any()))
                    .thenReturn(List.of());
            when(checkoutRepository.countByMerchantIdAndMode(MERCHANT, "test")).thenReturn(0L);

            mockMvc.perform(signed(get("/api/agentic/checkouts")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty())
                    .andExpect(jsonPath("$.total").value(0));

            verify(checkoutRepository)
                    .findByMerchantIdAndModeOrderByCreatedAtDesc(eq(MERCHANT), eq("test"), any());
        }

        @Test
        @DisplayName("a missing checkout is 404")
        void notFound() throws Exception {
            UUID id = UUID.randomUUID();
            when(checkoutService.require(eq(MERCHANT), eq("test"), eq(id)))
                    .thenThrow(ResourceNotFoundException.of("Checkout", id));
            mockMvc.perform(signed(get("/api/agentic/checkouts/" + id))).andExpect(status().isNotFound());
        }
    }

    // ── G-4 conversations / actions ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/agentic/conversations and /api/agentic/actions")
    class Listings {

        @Test
        @DisplayName("conversation list is merchant/mode scoped and paginated")
        void conversationList() throws Exception {
            when(conversationRepository.findByMerchantIdAndModeOrderByCreatedAtDesc(eq(MERCHANT), eq("test"), any()))
                    .thenReturn(List.of());
            when(conversationRepository.countByMerchantIdAndMode(MERCHANT, "test")).thenReturn(0L);

            mockMvc.perform(signed(get("/api/agentic/conversations").param("page", "2").param("limit", "10")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(2))
                    .andExpect(jsonPath("$.limit").value(10));

            verify(conversationRepository)
                    .findByMerchantIdAndModeOrderByCreatedAtDesc(eq(MERCHANT), eq("test"), any());
        }

        @Test
        @DisplayName("action list filters by payment_id when given, still scoped")
        void actionListByPayment() throws Exception {
            UUID paymentId = UUID.randomUUID();
            when(actionRepository.findByMerchantIdAndModeAndPaymentIdOrderByCreatedAtDesc(
                    eq(MERCHANT), eq("test"), eq(paymentId), any())).thenReturn(List.of());
            when(actionRepository.countByMerchantIdAndModeAndPaymentId(MERCHANT, "test", paymentId))
                    .thenReturn(0L);

            mockMvc.perform(signed(get("/api/agentic/actions").param("payment_id", paymentId.toString())))
                    .andExpect(status().isOk());

            verify(actionRepository).findByMerchantIdAndModeAndPaymentIdOrderByCreatedAtDesc(
                    eq(MERCHANT), eq("test"), eq(paymentId), any());
        }

        @Test
        @DisplayName("action by id resolves through the merchant-scoped journal finder")
        void actionById() throws Exception {
            UUID id = UUID.randomUUID();
            when(journal.requireAction(MERCHANT, "test", id))
                    .thenThrow(ResourceNotFoundException.of("AgentAction", id));
            mockMvc.perform(signed(get("/api/agentic/actions/" + id))).andExpect(status().isNotFound());

            ArgumentCaptor<UUID> captor = ArgumentCaptor.forClass(UUID.class);
            verify(journal).requireAction(eq(MERCHANT), eq("test"), captor.capture());
            assertThat(captor.getValue()).isEqualTo(id);
        }

        @Test
        @DisplayName("an unsigned action list is refused with 401")
        void unsigned() throws Exception {
            mockMvc.perform(get("/api/agentic/actions")).andExpect(status().isUnauthorized());
        }
    }

    // ── G-3 config ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/agentic/config")
    class Config {

        @Test
        @DisplayName("reports non-secret runtime config and never a credential value")
        void reportsConfig() throws Exception {
            mockMvc.perform(signed(get("/api/agentic/config")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mode").value("test"))
                    .andExpect(jsonPath("$.promptVersion").value("v1"))
                    .andExpect(jsonPath("$.llm.provider").exists())
                    .andExpect(jsonPath("$.llm.credentialConfigured").isBoolean())
                    // No field anywhere that could carry a key value.
                    .andExpect(jsonPath("$.llm.apiKey").doesNotExist())
                    .andExpect(jsonPath("$.razorpay.keySecret").doesNotExist())
                    .andExpect(jsonPath("$.policy.rules").isArray());
        }

        @Test
        @DisplayName("policy rules mirror the engine's rule catalogue exactly")
        void policyRulesMirrorEngine() throws Exception {
            // One row per PolicyRule constant; the waivable-count and threshold checks live in
            // PolicyCatalogTest, which does not go through JSON.
            mockMvc.perform(signed(get("/api/agentic/config")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.policy.rules.length()")
                            .value(com.paymentflow.agentic.policy.PolicyRule.values().length))
                    .andExpect(jsonPath("$.policy.rules[14].id").value("refund-approval-threshold"))
                    .andExpect(jsonPath("$.policy.rules[14].waivable").value(true));
        }

        @Test
        @DisplayName("an unsigned config request is refused with 401")
        void unsigned() throws Exception {
            mockMvc.perform(get("/api/agentic/config")).andExpect(status().isUnauthorized());
        }
    }

    // ── G-1 summary ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/agentic/summary")
    class Summary {

        @Test
        @DisplayName("aggregates persisted counts, scoped, with a default all-time window")
        void aggregates() throws Exception {
            mockMvc.perform(signed(get("/api/agentic/summary")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.source").value("persisted"))
                    .andExpect(jsonPath("$.conversations.total").value(0))
                    .andExpect(jsonPath("$.actions.total").value(0))
                    .andExpect(jsonPath("$.approvals.pending").value(0));

            verify(conversationRepository).countByMerchantIdAndModeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                    eq(MERCHANT), eq("test"), any(), any());
        }

        @Test
        @DisplayName("from after to is a 400")
        void badWindow() throws Exception {
            mockMvc.perform(signed(get("/api/agentic/summary")
                            .param("from", "2026-08-31T00:00:00Z")
                            .param("to", "2026-08-01T00:00:00Z")))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── G-6 provider decisions ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/agentic/provider-decisions")
    class ProviderDecisions {

        @Test
        @DisplayName("scopes by payment_id and merchant, and labels a demo approval as such")
        void byPaymentDistinguishesDemo() throws Exception {
            UUID paymentId = UUID.randomUUID();
            var demo = com.paymentflow.agentic.provider.ProviderDecisionRecord.of(
                    MERCHANT, "test", paymentId, "k1", "AUTHORIZE",
                    com.paymentflow.agentic.provider.ProviderDecision.demoApproved("order_1"),
                    "razorpay", 250000, "INR", "corr");
            when(providerDecisions.findByMerchantIdAndModeAndPaymentIdOrderByCreatedAtDesc(
                    MERCHANT, "test", paymentId)).thenReturn(List.of(demo));

            mockMvc.perform(signed(get("/api/agentic/provider-decisions")
                            .param("payment_id", paymentId.toString())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].kind").value("DEMO_ORDER_ACCEPTED"))
                    .andExpect(jsonPath("$.data[0].demoApproval").value(true))
                    .andExpect(jsonPath("$.data[0].outcome").value("APPROVE"));

            verify(providerDecisions)
                    .findByMerchantIdAndModeAndPaymentIdOrderByCreatedAtDesc(MERCHANT, "test", paymentId);
        }

        @Test
        @DisplayName("a real authorization is labelled REAL_AUTHORIZATION")
        void realAuthorization() throws Exception {
            UUID paymentId = UUID.randomUUID();
            var real = com.paymentflow.agentic.provider.ProviderDecisionRecord.of(
                    MERCHANT, "test", paymentId, "k2", "AUTHORIZE",
                    com.paymentflow.agentic.provider.ProviderDecision.approved(
                            com.paymentflow.agentic.provider.ProviderDecision.SOURCE_PAYMENT_COLLECTED, "pay_1"),
                    "razorpay", 250000, "INR", "corr");
            when(providerDecisions.findByMerchantIdAndModeAndPaymentIdOrderByCreatedAtDesc(
                    MERCHANT, "test", paymentId)).thenReturn(List.of(real));

            mockMvc.perform(signed(get("/api/agentic/provider-decisions")
                            .param("payment_id", paymentId.toString())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].kind").value("REAL_AUTHORIZATION"))
                    .andExpect(jsonPath("$.data[0].demoApproval").value(false));
        }

        @Test
        @DisplayName("unsigned is refused with 401")
        void unsigned() throws Exception {
            mockMvc.perform(get("/api/agentic/provider-decisions")).andExpect(status().isUnauthorized());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    private static ProductView productView() {
        return new ProductView(UUID.randomUUID().toString(), "SKU-1", "Thing", "desc", "cat",
                1000, "INR", true);
    }
}
