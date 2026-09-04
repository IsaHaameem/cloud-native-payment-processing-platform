import { type NextRequest } from 'next/server';

import { listCheckouts } from '@/lib/agentic/operations';
import { handleAgenticRead } from '@/lib/agentic/route';

/** `GET /api/agentic/checkouts` — checkouts the agent assembled (G-2). */
export const dynamic = 'force-dynamic';

export function GET(request: NextRequest) {
  const p = request.nextUrl.searchParams;
  return handleAgenticRead(request, (session) =>
    listCheckouts(session, {
      page: p.get('page') ? Number(p.get('page')) : undefined,
      limit: p.get('limit') ? Number(p.get('limit')) : undefined,
    }),
  );
}
