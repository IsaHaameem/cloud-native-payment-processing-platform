import { type NextRequest } from 'next/server';

import { listConversations } from '@/lib/agentic/operations';
import { handleAgenticRead } from '@/lib/agentic/route';

/** `GET /api/agentic/conversations` — the conversation list (G-4). */
export const dynamic = 'force-dynamic';

export function GET(request: NextRequest) {
  const p = request.nextUrl.searchParams;
  return handleAgenticRead(request, (session) =>
    listConversations(session, {
      page: p.get('page') ? Number(p.get('page')) : undefined,
      limit: p.get('limit') ? Number(p.get('limit')) : undefined,
    }),
  );
}
