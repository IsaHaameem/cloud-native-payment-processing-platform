import { type NextRequest } from 'next/server';

import { listProviderDecisions } from '@/lib/agentic/operations';
import { handleAgenticRead } from '@/lib/agentic/route';

/** `GET /api/agentic/provider-decisions` — real vs demo, distinguished by `kind` (G-6). */
export const dynamic = 'force-dynamic';

export function GET(request: NextRequest) {
  const p = request.nextUrl.searchParams;
  return handleAgenticRead(request, (session) =>
    listProviderDecisions(session, {
      paymentId: p.get('payment_id') ?? undefined,
      page: p.get('page') ? Number(p.get('page')) : undefined,
      limit: p.get('limit') ? Number(p.get('limit')) : undefined,
    }),
  );
}
