import { type NextRequest } from 'next/server';

import { listApprovals } from '@/lib/agentic/operations';
import { handleAgenticRead } from '@/lib/agentic/route';

/**
 * `GET /api/agentic/approvals` — the pending approval queue for the session's merchant.
 *
 * Proxies `agentic-commerce-service`'s `GET /api/agentic/approvals` with a signed internal
 * context. The client (`approvals` screen) polls this; a decision is a Server Action.
 */
export const dynamic = 'force-dynamic';

export function GET(request: NextRequest) {
  return handleAgenticRead(request, (session) => listApprovals(session));
}
