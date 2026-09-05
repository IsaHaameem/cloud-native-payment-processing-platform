import { type NextRequest } from 'next/server';

import { getAgenticConfig } from '@/lib/agentic/operations';
import { handleAgenticRead } from '@/lib/agentic/route';

/** `GET /api/agentic/config` — runtime config + the policy the engine enforces (G-3). */
export const dynamic = 'force-dynamic';

export function GET(request: NextRequest) {
  return handleAgenticRead(request, (session) => getAgenticConfig(session));
}
