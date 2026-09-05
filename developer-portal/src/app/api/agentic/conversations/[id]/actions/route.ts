import { type NextRequest } from 'next/server';

import { getConversationActions } from '@/lib/agentic/operations';
import { handleAgenticRead } from '@/lib/agentic/route';

/**
 * `GET /api/agentic/conversations/{id}/actions` — the full action trail for a conversation.
 *
 * Proxies `agentic-commerce-service`'s `GET /api/agentic/conversations/{id}/actions`. The dev
 * console polls this after a turn to render the trace as the steps land.
 */
export const dynamic = 'force-dynamic';

export function GET(request: NextRequest, context: { params: Promise<{ id: string }> }) {
  return handleAgenticRead(request, async (session) => {
    const { id } = await context.params;
    return getConversationActions(session, id);
  });
}
