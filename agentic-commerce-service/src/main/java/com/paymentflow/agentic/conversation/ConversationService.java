package com.paymentflow.agentic.conversation;

import com.paymentflow.agentic.error.AgenticErrorCode;
import com.paymentflow.agentic.error.AgenticException;
import com.paymentflow.common.exception.ResourceNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Owns conversations and their transcripts, and nothing else.
 *
 * <p>Specifically, it owns no financial rule. It counts tool calls and accumulates spend
 * because {@code PolicyEngine} needs those numbers, but it does not decide what they mean —
 * the thresholds are configuration and the comparison is the engine's. This service is the
 * bookkeeper, not the judge.
 *
 * <h2>Counter updates are their own transactions</h2>
 *
 * <p>{@link #recordSpend} and {@link #recordRefund} run in {@link Propagation#REQUIRES_NEW}.
 * A budget that is only debited when the surrounding transaction commits is a budget an agent
 * can overspend by failing at the right moment; crediting it in its own transaction, straight
 * after the platform accepted the money movement, means the next check sees it even if
 * everything downstream then rolls back.
 */
@Service
public class ConversationService {

    /**
     * How many past turns are sent to the model.
     *
     * <p>Bounded because a prompt that grows with the conversation grows its cost and its
     * latency without bound, and because nothing financial depends on remembering further back
     * — every money action re-reads the state it acts on. Twenty turns is comfortably more
     * context than a purchase conversation needs.
     */
    private static final int TRANSCRIPT_WINDOW = 20;

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;

    public ConversationService(ConversationRepository conversationRepository,
                              ConversationMessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public Conversation start(UUID merchantId, String mode, String sessionRef) {
        return conversationRepository.save(Conversation.start(merchantId, mode, sessionRef));
    }

    /** Loads a conversation, or 404s. Merchant- and mode-scoped, so another tenant's is simply absent. */
    @Transactional(readOnly = true)
    public Conversation require(UUID merchantId, String mode, UUID conversationId) {
        return conversationRepository.findByIdAndMerchantIdAndMode(conversationId, merchantId, mode)
                .orElseThrow(() -> ResourceNotFoundException.of("Conversation", conversationId));
    }

    /**
     * Loads a conversation that is allowed to act.
     *
     * <p>Checked here as well as in the policy engine. Two independent refusals, because this
     * one produces a clean error before a turn starts and the engine's catches anything that
     * reaches it another way.
     */
    @Transactional(readOnly = true)
    public Conversation requireActive(UUID merchantId, String mode, UUID conversationId) {
        Conversation conversation = require(merchantId, mode, conversationId);
        if (!conversation.isActive()) {
            throw new AgenticException(AgenticErrorCode.CONVERSATION_CLOSED);
        }
        return conversation;
    }

    @Transactional
    public Conversation close(UUID merchantId, String mode, UUID conversationId) {
        Conversation conversation = require(merchantId, mode, conversationId);
        conversation.close();
        return conversationRepository.save(conversation);
    }

    // ── Transcript ──────────────────────────────────────────────────────────────────────

    /** Appends a turn. Content is redacted and bounded by {@link ConversationMessage#of}. */
    @Transactional
    public ConversationMessage append(UUID conversationId, ConversationMessage.Role role, String content) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ResourceNotFoundException.of("Conversation", conversationId));
        int sequenceNo = messageRepository.countForConversation(conversationId) + 1;
        return messageRepository.save(ConversationMessage.of(conversation, role, content, sequenceNo));
    }

    /** The whole transcript, oldest first. For the API and the audit view, not for the prompt. */
    @Transactional(readOnly = true)
    public List<ConversationMessage> transcript(UUID conversationId) {
        return messageRepository.findTranscript(conversationId);
    }

    /** The bounded window sent to the model, oldest first. */
    @Transactional(readOnly = true)
    public List<ConversationMessage> promptWindow(UUID conversationId) {
        List<ConversationMessage> newestFirst = messageRepository
                .findRecent(conversationId, PageRequest.of(0, TRANSCRIPT_WINDOW));
        List<ConversationMessage> oldestFirst = new ArrayList<>(newestFirst);
        oldestFirst.sort((left, right) -> Integer.compare(left.getSequenceNo(), right.getSequenceNo()));
        return oldestFirst;
    }

    // ── Counters ────────────────────────────────────────────────────────────────────────

    /**
     * Counts one tool call against the conversation's ceiling.
     *
     * <p>Its own transaction, and called when the call is <em>attempted</em>. An agent looping
     * on failures is exactly what the ceiling exists to stop, and a counter that only advanced
     * on success would never reach it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordToolCall(UUID conversationId) {
        conversationRepository.findById(conversationId).ifPresent(conversation -> {
            conversation.recordToolCall();
            conversationRepository.save(conversation);
        });
    }

    /** Credits a payment the platform accepted. Never called speculatively. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSpend(UUID conversationId, long amountMinor) {
        conversationRepository.findById(conversationId).ifPresent(conversation -> {
            conversation.recordSpend(amountMinor);
            conversationRepository.save(conversation);
        });
    }

    /** Credits a refund the platform accepted. Never called speculatively. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRefund(UUID conversationId, long amountMinor) {
        conversationRepository.findById(conversationId).ifPresent(conversation -> {
            conversation.recordRefund(amountMinor);
            conversationRepository.save(conversation);
        });
    }
}
