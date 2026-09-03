package com.paymentflow.agentic.llm;

import com.paymentflow.agentic.error.AgenticErrorCode;
import com.paymentflow.agentic.error.AgenticException;

/**
 * The model could not be reached, or refused to answer.
 *
 * <p><b>An unavailable model is not a payment problem.</b> It stops the agent from deciding
 * what to do next; it says nothing about any tool call already executed, and it must never
 * cause one to be repeated. The runtime's response is to end the turn with an honest failure —
 * every action already taken keeps its own recorded outcome, and the reply says the assistant
 * could not continue rather than inventing a summary of what happened.
 *
 * <p>The one thing that must not happen here is a cheerful fallback. An agent that answers
 * "your payment went through" because it could not reach the model to ask is worse than one
 * that says nothing at all.
 */
public class LlmUnavailableException extends AgenticException {

    public LlmUnavailableException(String message) {
        super(AgenticErrorCode.LLM_UNAVAILABLE, message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(AgenticErrorCode.LLM_UNAVAILABLE, message, cause);
    }
}
