import { NextResponse } from 'next/server';

/**
 * Liveness for the container healthcheck and for compose's `depends_on` (M23.1).
 *
 * Deliberately shallow. It answers "is this process serving HTTP", and nothing else — it does
 * not reach the gateway, because a portal that reports itself unhealthy during a backend blip
 * gets restarted by the orchestrator, which fixes nothing and turns a degraded dependency into
 * a restart loop. The platform's own health is the platform's to report.
 */
export const dynamic = 'force-dynamic';

export function GET() {
  return NextResponse.json({ status: 'UP' }, { status: 200 });
}
