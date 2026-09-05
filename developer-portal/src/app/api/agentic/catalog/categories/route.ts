import { type NextRequest } from 'next/server';

import { listProductCategories } from '@/lib/agentic/operations';
import { handleAgenticRead } from '@/lib/agentic/route';

/** `GET /api/agentic/catalog/categories` — distinct categories, for the filter (G-2). */
export const dynamic = 'force-dynamic';

export function GET(request: NextRequest) {
  return handleAgenticRead(request, (session) => listProductCategories(session));
}
