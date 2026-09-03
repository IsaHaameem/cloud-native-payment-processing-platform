package com.paymentflow.agentic.web;

import com.paymentflow.agentic.action.AgentActionJournal;
import com.paymentflow.agentic.conversation.Conversation;
import com.paymentflow.agentic.conversation.ConversationRepository;
import com.paymentflow.agentic.conversation.ConversationService;
import com.paymentflow.agentic.runtime.AgentRuntime;
import com.paymentflow.agentic.runtime.AgentTurnResult;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The API the demo front end drives.
 *
 * <pre>
 *   POST /api/agentic/conversations                    start one
 *   GET  /api/agentic/conversations?page=&limit=       list them, newest first (G-4)
 *   POST /api/agentic/conversations/{id}/messages      say something; the agent acts and replies
 *   GET  /api/agentic/conversations/{id}               the conversation and its transcript
 *   GET  /api/agentic/conversations/{id}/actions       the full action trail
 * </pre>
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>No endpoint exposes the policy engine, the tool registry, the platform client, or any
 * payment operation directly. The browser cannot ask this service to evaluate a policy, execute
 * a named tool, or make a platform call — those are reachable only <em>through</em> a
 * conversation turn, which is what guarantees every one of them passed the full pipeline. An
 * endpoint that executed a tool by name would be a way around the agent, and therefore a way
 * around the model's own bounds.
 *
 * <p>The front end also never talks to the language-model provider. It talks to this service;
 * this service holds the credential and decides what the model is shown.
 *
 * <h2>Authentication</h2>
 *
 * <p>Every request carries an HMAC-verified internal context asserted by the developer portal's
 * server-side proxy — merchant, mode and user derived from the authenticated session, signed the
 * same way the gateway signs a {@code /v1} context (D100/D185). {@link AgenticCallerContext}
 * reads it. The merchant and mode come from that context, never from the request, so a caller
 * cannot point this API at another tenant, and an unsigned request is refused before this
 * controller runs (see {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/agentic/conversations")
public class AgentController {

    private final AgentRuntime runtime;
    private final ConversationService conversations;
    private final ConversationRepository conversationRepository;
    private final AgentActionJournal journal;
    private final AgenticCallerContext callerContext;

    public AgentController(AgentRuntime runtime, ConversationService conversations,
                           ConversationRepository conversationRepository, AgentActionJournal journal,
                           AgenticCallerContext callerContext) {
        this.runtime = runtime;
        this.conversations = conversations;
        this.conversationRepository = conversationRepository;
        this.journal = journal;
        this.callerContext = callerContext;
    }

    /**
     * The conversation list (G-4), newest first. Header facts only — the transcript is on the
     * detail endpoint. Merchant- and mode-scoped from the verified context.
     */
    @GetMapping
    public PageResponse<AgentDtos.ConversationSummary> list(@RequestParam(required = false) Integer page,
                                                            @RequestParam(required = false) Integer limit) {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        int clampedPage = PageResponse.clampPage(page);
        int clampedLimit = PageResponse.clampLimit(limit);
        List<Conversation> rows = conversationRepository.findByMerchantIdAndModeOrderByCreatedAtDesc(
                caller.merchantId(), caller.mode(), PageRequest.of(clampedPage, clampedLimit));
        long total = conversationRepository.countByMerchantIdAndMode(caller.merchantId(), caller.mode());
        return PageResponse.of(rows, clampedPage, clampedLimit, total, AgentDtos.ConversationSummary::of);
    }

    @PostMapping
    public ResponseEntity<AgentDtos.ConversationResponse> start(
            @Valid @RequestBody AgentDtos.StartConversationRequest request) {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        Conversation conversation = conversations.start(caller.merchantId(), caller.mode(),
                request.sessionRef());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AgentDtos.ConversationResponse.of(conversation, List.of()));
    }

    /**
     * One turn. The agent may call several tools before replying, or none.
     *
     * <p>Always {@code 200}, including when the turn stopped at an approval, a limit or an
     * outage — those are outcomes the caller has to render, not transport failures. The
     * {@code stopReason} field is what distinguishes them.
     */
    @PostMapping("/{conversationId}/messages")
    public AgentDtos.TurnResponse sendMessage(@PathVariable UUID conversationId,
                                              @Valid @RequestBody AgentDtos.SendMessageRequest request) {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        // Loaded here for its own session reference: that is written to policy_decisions.actor
        // alongside the verified caller, and an actor field that says the same thing for every
        // conversation is not an audit trail.
        Conversation conversation = conversations.require(caller.merchantId(), caller.mode(), conversationId);
        AgentTurnResult result = runtime.handleUserMessage(
                new AgentRuntime.Caller(caller.merchantId(), caller.mode(),
                        conversation.getSessionRef(), caller.actor()),
                conversationId, request.message());
        return AgentDtos.TurnResponse.of(result);
    }

    @GetMapping("/{conversationId}")
    public AgentDtos.ConversationResponse get(@PathVariable UUID conversationId) {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        Conversation conversation = conversations.require(caller.merchantId(), caller.mode(), conversationId);
        return AgentDtos.ConversationResponse.of(conversation, conversations.transcript(conversationId));
    }

    /**
     * The complete action trail, newest first.
     *
     * <p>This is the endpoint the demo's audit view reads, and the reason AD-12 could drop
     * {@code get_audit_trail} as a tool: not everything the demo shows needs to be something the
     * model can ask for. Showing the model its own audit trail would let it narrate one.
     */
    @GetMapping("/{conversationId}/actions")
    public List<AgentDtos.ActionTrailResponse> actions(@PathVariable UUID conversationId) {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        conversations.require(caller.merchantId(), caller.mode(), conversationId);
        return journal.actionsForConversation(conversationId).stream()
                .map(AgentDtos.ActionTrailResponse::of)
                .toList();
    }
}
