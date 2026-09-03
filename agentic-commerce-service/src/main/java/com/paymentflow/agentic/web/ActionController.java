package com.paymentflow.agentic.web;

import com.paymentflow.agentic.action.AgentAction;
import com.paymentflow.agentic.action.AgentActionJournal;
import com.paymentflow.agentic.action.AgentActionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The cross-conversation action index (G-4).
 *
 * <pre>
 *   GET /api/agentic/actions?page=&limit=&payment_id=   every action this merchant's agent took
 *   GET /api/agentic/actions/{id}                        one action's full trail
 * </pre>
 *
 * <p>Until now the action trail was reachable only per conversation. This is the flat listing —
 * the answer to "show me everything the agent has done", and, with {@code payment_id}, "show me
 * the actions behind this payment". Every row is the same {@link AgentDtos.ActionTrailResponse}
 * the per-conversation endpoint returns: tool, policy decision, approval, the platform steps with
 * their derived idempotency keys, the correlation id, the redacted input summary. Merchant- and
 * mode-scoped from the verified context; another merchant's action is simply absent (404), never
 * returned.
 */
@RestController
@RequestMapping("/api/agentic/actions")
public class ActionController {

    private final AgentActionRepository actionRepository;
    private final AgentActionJournal journal;
    private final AgenticCallerContext callerContext;

    public ActionController(AgentActionRepository actionRepository, AgentActionJournal journal,
                            AgenticCallerContext callerContext) {
        this.actionRepository = actionRepository;
        this.journal = journal;
        this.callerContext = callerContext;
    }

    @GetMapping
    public PageResponse<AgentDtos.ActionTrailResponse> list(
            @RequestParam(name = "payment_id", required = false) UUID paymentId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer limit) {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        int clampedPage = PageResponse.clampPage(page);
        int clampedLimit = PageResponse.clampLimit(limit);
        PageRequest pageRequest = PageRequest.of(clampedPage, clampedLimit);

        List<AgentAction> rows;
        long total;
        if (paymentId != null) {
            rows = actionRepository.findByMerchantIdAndModeAndPaymentIdOrderByCreatedAtDesc(
                    caller.merchantId(), caller.mode(), paymentId, pageRequest);
            total = actionRepository.countByMerchantIdAndModeAndPaymentId(
                    caller.merchantId(), caller.mode(), paymentId);
        } else {
            rows = actionRepository.findByMerchantIdAndModeOrderByCreatedAtDesc(
                    caller.merchantId(), caller.mode(), pageRequest);
            total = actionRepository.countByMerchantIdAndMode(caller.merchantId(), caller.mode());
        }
        return PageResponse.of(rows, clampedPage, clampedLimit, total, AgentDtos.ActionTrailResponse::of);
    }

    @GetMapping("/{id}")
    public AgentDtos.ActionTrailResponse get(@PathVariable UUID id) {
        AgenticCallerContext.Caller caller = callerContext.resolve();
        return AgentDtos.ActionTrailResponse.of(
                journal.requireAction(caller.merchantId(), caller.mode(), id));
    }
}
