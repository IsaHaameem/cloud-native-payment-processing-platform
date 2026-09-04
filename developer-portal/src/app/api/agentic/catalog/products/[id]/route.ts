import { type NextRequest } from 'next/server';

import { getProduct } from '@/lib/agentic/operations';
import { handleAgenticRead } from '@/lib/agentic/route';

/** `GET /api/agentic/catalog/products/{id}` (G-2). */
export const dynamic = 'force-dynamic';

export function GET(request: NextRequest, context: { params: Promise<{ id: string }> }) {
  return handleAgenticRead(request, async (session) => {
    const { id } = await context.params;
    return getProduct(session, id);
  });
}
