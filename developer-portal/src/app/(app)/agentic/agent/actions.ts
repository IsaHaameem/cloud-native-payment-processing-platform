'use server';

import { randomUUID } from 'node:crypto';

import { sendMessage, startConversation } from '@/lib/agentic/operations';
import { AuthenticationError, PlatformError } from '@/lib/api/errors';
import { GUARD_MESSAGES, guardFormRequest } from '@/lib/security/form-guard';
import { readSession } from '@/lib/session/require';

import { AGENT_CONSOLE_IDLE, type AgentConsoleState } from './action-state';

/**
 * The dev-only "Try it" console on `/agentic/agent`, as a guarded Server Action.
 *
 * `frontend_Design.md` is explicit that the portal is observability, not a chat client — the
 * agent's buyer-facing surface is elsewhere. This console exists so the end-to-end path
 * (message → model → tool → policy → approval → platform → trail) can be exercised and watched
 * from inside the portal in **test mode only**. It is labelled as such in the UI and it drives
 * exactly the same pipeline every other caller does; it has no privileged path.
 *
 * A turn is a mutation, so it is here: CSRF token and origin assertion apply. The first message
 * of a session starts a conversation; every later one continues it via the `conversationId` the
 * previous turn returned.
 */

function fail(
  message: string,
  conversationId: string | undefined,
  platform?: PlatformError,
): AgentConsoleState {
  return { ...AGENT_CONSOLE_IDLE, error: message, requestId: platform?.requestId, conversationId };
}

export async function sendAgentMessageAction(
  _previous: AgentConsoleState,
  formData: FormData,
): Promise<AgentConsoleState> {
  const existingConversationId = normaliseId(formData.get('conversationId'));

  const refused = await guardFormRequest(formData.get('csrfToken'));
  if (refused) return fail(GUARD_MESSAGES[refused], existingConversationId);

  const session = await readSession();
  if (!session)
    return fail('Your session is no longer valid. Sign in again.', existingConversationId);
  if (session.merchantId === undefined) {
    return fail('Finish onboarding before using agentic commerce.', existingConversationId);
  }
  const merchantSession = session as typeof session & { merchantId: string };

  const message = String(formData.get('message') ?? '').trim();
  if (message === '') return fail('Type a message for the agent.', existingConversationId);
  if (message.length > 4000) {
    return fail('Keep the message under 4000 characters.', existingConversationId);
  }

  const correlationId = randomUUID();

  try {
    let conversationId = existingConversationId;
    if (conversationId === undefined) {
      const conversation = await startConversation(
        merchantSession,
        `portal-console:${session.userId}`,
      );
      conversationId = conversation.id;
    }

    const turn = await sendMessage(merchantSession, conversationId, message, correlationId);
    return {
      ok: true,
      error: undefined,
      requestId: undefined,
      conversationId: turn.conversationId,
      sentMessage: message,
      turn,
    };
  } catch (error) {
    if (error instanceof AuthenticationError) {
      return fail('Your session is no longer valid. Sign in again.', existingConversationId);
    }
    if (error instanceof PlatformError) {
      return fail(error.message, existingConversationId, error);
    }
    throw error;
  }
}

/** A blank or absent hidden field arrives as `''` or `null`; both mean "no conversation yet". */
function normaliseId(value: FormDataEntryValue | null): string | undefined {
  const text = typeof value === 'string' ? value.trim() : '';
  return text === '' ? undefined : text;
}
