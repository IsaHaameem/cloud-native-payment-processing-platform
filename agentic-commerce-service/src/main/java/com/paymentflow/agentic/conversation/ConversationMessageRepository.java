package com.paymentflow.agentic.conversation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Reads over a conversation's transcript.
 *
 * <p>Keyed by conversation rather than by {@code (merchantId, mode)} — safe for the same reason
 * {@code PolicyDecisionRepository} is: a message is only ever reached through a conversation
 * that was itself loaded through a merchant-scoped finder. There is no path that starts at a
 * message id.
 *
 * <h2>Why these are explicit queries and not derived ones</h2>
 *
 * <p>{@link ConversationMessage} maps its parent as a {@code @ManyToOne conversation} and also
 * exposes a convenience {@code getConversationId()}. Spring Data resolves a derived
 * {@code findByConversationId} against <em>properties</em>, finds that getter, and emits
 * {@code c.conversationId} — an attribute Hibernate has no mapping for, because the mapped one
 * is the association. The result is a query that builds fine and fails at call time with
 * {@code UnknownPathException}.
 *
 * <p>That is exactly what happened here, and it was found by starting the service rather than by
 * any unit test: the repository was mocked everywhere it was used, so nothing ever asked
 * Hibernate to parse the query. Spelling the JPQL out removes the ambiguity entirely — the path
 * is {@code m.conversation.id}, which is the mapped attribute, and it cannot be re-derived into
 * something else by a getter added later.
 */
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, UUID> {

    @Query("select m from ConversationMessage m where m.conversation.id = :conversationId "
            + "order by m.sequenceNo asc")
    List<ConversationMessage> findTranscript(@Param("conversationId") UUID conversationId);

    /**
     * The most recent turns, newest first.
     *
     * <p>Used to build the prompt. A conversation's whole history is not sent every turn: the
     * window is bounded so a long chat cannot grow the request without limit, which is a cost
     * and a latency problem long before it is a context-window one.
     */
    @Query("select m from ConversationMessage m where m.conversation.id = :conversationId "
            + "order by m.sequenceNo desc")
    List<ConversationMessage> findRecent(@Param("conversationId") UUID conversationId, Pageable pageable);

    @Query("select count(m) from ConversationMessage m where m.conversation.id = :conversationId")
    int countForConversation(@Param("conversationId") UUID conversationId);
}
