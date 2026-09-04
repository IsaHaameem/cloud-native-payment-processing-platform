import { type NextRequest } from 'next/server';

import { getConversation } from '@/lib/agentic/operations';
import { handleAgenticRead } from '@/lib/agentic/route';

/**
 * `GET /api/agentic/conversations/{id}` — the conversation and its transcript.
 *
 * Proxies `agentic-commerce-service`'s `GET /api/agentic/conversations/{id}`. Read-only; a turn
 * is a Server Action.
 */
export const dynamic = 'force-dynamic';

export function GET(request: NextRequest, context: { params: Promise<{ id: string }> }) {
  return handleAgenticRead(request, async (session) => {
    const { id } = await context.params;
    return getConversation(session, id);
  });
}
