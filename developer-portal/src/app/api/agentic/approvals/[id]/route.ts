import { type NextRequest } from 'next/server';

import { getApproval } from '@/lib/agentic/operations';
import { handleAgenticRead } from '@/lib/agentic/route';

/**
 * `GET /api/agentic/approvals/{id}` — one approval, by id.
 *
 * Proxies `agentic-commerce-service`'s `GET /api/agentic/approvals/{id}`. Read-only; approve and
 * deny are Server Actions.
 */
export const dynamic = 'force-dynamic';

export function GET(request: NextRequest, context: { params: Promise<{ id: string }> }) {
  return handleAgenticRead(request, async (session) => {
    const { id } = await context.params;
    return getApproval(session, id);
  });
}
