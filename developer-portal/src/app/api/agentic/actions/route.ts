import { type NextRequest } from 'next/server';

import { listActions } from '@/lib/agentic/operations';
import { handleAgenticRead } from '@/lib/agentic/route';

/** `GET /api/agentic/actions` — the cross-conversation action index (G-4). */
export const dynamic = 'force-dynamic';

export function GET(request: NextRequest) {
  const p = request.nextUrl.searchParams;
  return handleAgenticRead(request, (session) =>
    listActions(session, {
      paymentId: p.get('payment_id') ?? undefined,
      page: p.get('page') ? Number(p.get('page')) : undefined,
      limit: p.get('limit') ? Number(p.get('limit')) : undefined,
    }),
  );
}
