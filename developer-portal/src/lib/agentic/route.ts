import 'server-only';

import { NextResponse } from 'next/server';

import { AuthenticationError, PlatformError } from '@/lib/api/errors';
import { isSameOrigin } from '@/lib/security/origin';
import { type MerchantSession, readSession } from '@/lib/session/require';

/**
 * The shared body of every `/api/agentic/*` **read** route handler.
 *
 * These are the client-side read path for the agentic screens — the equivalent of
 * `/api/platform/[operation]` for the gateway, and it makes the same three promises:
 *
 * - **Reads only.** Mutations (an agent turn, an approval decision) are Server Actions, where the
 *   CSRF token and the confirmation dialog live. Nothing here can be turned into one.
 * - **Same-origin only**, and a session is required. A 401 is returned as JSON, not a redirect,
 *   so the client layer can send the user to sign in itself.
 * - **Never chooses the merchant.** It comes from the sealed session, asserted to the agentic
 *   service in the signed internal context. The browser names an approval or a conversation by
 *   id and nothing else.
 */
export async function handleAgenticRead<T>(
  request: Request,
  run: (session: MerchantSession) => Promise<T>,
): Promise<NextResponse> {
  if (!isSameOrigin(request.headers)) {
    return problem(403, 'forbidden', 'This request did not come from the portal.');
  }

  const session = await readSession();
  if (!session) {
    return problem(401, 'unauthorized', 'This request needs a session.');
  }
  if (session.merchantId === undefined) {
    return problem(403, 'no_merchant', 'This session has not completed onboarding.');
  }

  try {
    const data = await run(session as MerchantSession);
    return NextResponse.json(data, { headers: { 'Cache-Control': 'no-store' } });
  } catch (error) {
    if (error instanceof AuthenticationError) {
      return problem(401, 'unauthorized', 'This session is no longer valid.');
    }
    if (error instanceof PlatformError) {
      return NextResponse.json(
        {
          error: {
            type: error.type,
            code: error.code,
            message: error.message,
            requestId: error.requestId,
          },
        },
        { status: error.status, headers: { 'Cache-Control': 'no-store' } },
      );
    }
    throw error;
  }
}

function problem(status: number, code: string, message: string): NextResponse {
  return NextResponse.json(
    { error: { type: undefined, code, message, requestId: undefined } },
    { status, headers: { 'Cache-Control': 'no-store' } },
  );
}
