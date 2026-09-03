package com.paymentflow.agentic.tool.money;

import com.paymentflow.agentic.platform.PaymentFlowClient;
import com.paymentflow.agentic.platform.TestCardView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The set of payment instruments the agent may use — <b>the platform's own list, not one kept
 * here</b>.
 *
 * <p>AD-12 requires {@code complete_checkout}'s {@code instrumentToken} to come "from an
 * allow-list the UI supplies, never model-invented". This class is what makes that
 * enforceable. The permitted set is fetched from {@code GET /v1/test/cards}, which is the
 * platform's own catalogue of test tokens and the same source its documentation renders from —
 * so a token the model invented, or one that used to exist, is rejected before any payment is
 * created rather than declined later for a reason nobody can explain.
 *
 * <p>Maintaining a hard-coded list here was the obvious alternative and is the wrong one: it
 * would drift from the platform silently, and the direction it drifts in is "this service
 * believes a token is valid when it is not", which surfaces as a mysterious failure mid-payment.
 *
 * <h2>Fail closed</h2>
 *
 * <p>If the catalogue cannot be fetched and nothing is cached, <b>every token is refused</b>.
 * An unavailable allow-list means this service cannot demonstrate the instrument was not
 * invented, and the safe reading of "cannot demonstrate" is no. The cost of being wrong in that
 * direction is a payment that does not happen; the cost of the other direction is a payment
 * made against an instrument nobody chose.
 *
 * <p>A stale cache is served rather than refused when a refresh fails: the tokens in it were
 * genuinely on the platform's list minutes ago, which is a far better basis for a decision than
 * nothing at all.
 */
@Component
public class InstrumentAllowList {

    private static final Logger log = LoggerFactory.getLogger(InstrumentAllowList.class);

    /**
     * How long the catalogue is trusted before it is re-fetched. Long, because this list changes
     * roughly never — it is a fixture of the platform's test mode — and short enough that a
     * newly-added token becomes usable within a demo rather than requiring a restart.
     */
    private static final Duration TTL = Duration.ofMinutes(10);

    private final PaymentFlowClient client;
    private final Clock clock;

    private volatile Set<String> tokens = Set.of();
    private volatile Instant refreshedAt = Instant.EPOCH;

    public InstrumentAllowList(PaymentFlowClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    /**
     * Whether this token may be used to pay.
     *
     * <p>Case-sensitive and exact. A token is an identifier from a closed set, and accepting a
     * near-miss would be accepting something the model composed rather than something it was
     * given.
     */
    public boolean isPermitted(String instrumentToken) {
        if (instrumentToken == null || instrumentToken.isBlank()) {
            return false;
        }
        return permittedTokens().contains(instrumentToken);
    }

    /** The current allow-list, refreshed if stale. Empty when the catalogue has never been reachable. */
    public Set<String> permittedTokens() {
        if (isFresh()) {
            return tokens;
        }
        return refresh();
    }

    private boolean isFresh() {
        return !tokens.isEmpty() && Duration.between(refreshedAt, clock.instant()).compareTo(TTL) < 0;
    }

    private synchronized Set<String> refresh() {
        // Re-checked inside the lock: several concurrent tool calls arriving after expiry
        // should cost one fetch, not one each.
        if (isFresh()) {
            return tokens;
        }
        try {
            List<TestCardView> cards = client.listTestCards(null).body();
            Set<String> fetched = cards.stream()
                    .map(TestCardView::token)
                    .filter(token -> token != null && !token.isBlank())
                    .collect(Collectors.toUnmodifiableSet());

            if (fetched.isEmpty()) {
                log.warn("The platform's test-instrument catalogue was empty; keeping {} cached token(s).",
                        tokens.size());
                return tokens;
            }
            this.tokens = fetched;
            this.refreshedAt = clock.instant();
            log.info("Instrument allow-list refreshed: {} permitted token(s).", fetched.size());
            return fetched;
        } catch (RuntimeException e) {
            // Deliberately not rethrown. With a cache, the stale list is a better basis for a
            // decision than an exception; without one, the empty set refuses everything, which
            // is the same answer an exception would have produced but with a message the agent
            // can actually explain to a buyer.
            log.warn("Could not refresh the instrument allow-list; {} cached token(s) remain in force.",
                    tokens.size(), e);
            return tokens;
        }
    }
}
