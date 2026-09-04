import { type NextRequest } from 'next/server';

import { listProducts } from '@/lib/agentic/operations';
import { handleAgenticRead } from '@/lib/agentic/route';

/** `GET /api/agentic/catalog/products` — the merchant catalogue (G-2). */
export const dynamic = 'force-dynamic';

export function GET(request: NextRequest) {
  const p = request.nextUrl.searchParams;
  return handleAgenticRead(request, (session) =>
    listProducts(session, {
      query: p.get('query') ?? undefined,
      category: p.get('category') ?? undefined,
      page: p.get('page') ? Number(p.get('page')) : undefined,
      limit: p.get('limit') ? Number(p.get('limit')) : undefined,
    }),
  );
}
