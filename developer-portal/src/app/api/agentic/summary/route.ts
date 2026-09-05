import { type NextRequest } from 'next/server';

import { getAgenticSummary } from '@/lib/agentic/operations';
import { handleAgenticRead } from '@/lib/agentic/route';

/** `GET /api/agentic/summary` — the persisted aggregate (G-1). */
export const dynamic = 'force-dynamic';

export function GET(request: NextRequest) {
  const p = request.nextUrl.searchParams;
  return handleAgenticRead(request, (session) =>
    getAgenticSummary(session, {
      from: p.get('from') ?? undefined,
      to: p.get('to') ?? undefined,
    }),
  );
}
