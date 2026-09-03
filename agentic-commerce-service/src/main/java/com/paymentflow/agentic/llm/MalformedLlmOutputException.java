package com.paymentflow.agentic.llm;

import com.paymentflow.agentic.error.AgenticErrorCode;
import com.paymentflow.agentic.error.AgenticException;

/**
 * The provider answered with something this adapter cannot read.
 *
 * <p>A missing {@code content} array, a {@code tool_use} block with no name, an {@code input}
 * that is not an object, a body that is not JSON at all.
 *
 * <p><b>Nothing is repaired.</b> No heuristic parse of the text, no inferring a tool name from
 * prose, no filling in an argument the block did not carry. The reasoning is narrow and worth
 * stating plainly: the arguments in question decide what gets charged, and a repaired financial
 * tool call is a charge assembled by a regular expression. A malformed response is recorded and
 * the turn ends having executed nothing.
 *
 * <p>This is deliberately distinct from {@link LlmUnavailableException}. Unreachable means try
 * later; malformed means the provider is answering, and this adapter and it disagree about the
 * contract — which is an operator's problem, not a retry's.
 */
public class MalformedLlmOutputException extends AgenticException {

    public MalformedLlmOutputException(String message) {
        super(AgenticErrorCode.LLM_OUTPUT_INVALID, message);
    }

    public MalformedLlmOutputException(String message, Throwable cause) {
        super(AgenticErrorCode.LLM_OUTPUT_INVALID, message, cause);
    }
}
