import { type NextRequest } from 'next/server';

import { getAction } from '@/lib/agentic/operations';
import { handleAgenticRead } from '@/lib/agentic/route';

/** `GET /api/agentic/actions/{id}` (G-4). */
export const dynamic = 'force-dynamic';

export function GET(request: NextRequest, context: { params: Promise<{ id: string }> }) {
  return handleAgenticRead(request, async (session) => {
    const { id } = await context.params;
    return getAction(session, id);
  });
}
