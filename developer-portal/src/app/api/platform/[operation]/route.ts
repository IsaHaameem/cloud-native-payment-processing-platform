import { type NextRequest, NextResponse } from 'next/server';

import { callAs } from '@/lib/api/client';
import { AuthenticationError, PlatformError } from '@/lib/api/errors';
import { readOperation } from '@/lib/api/read-operations';
import { type OperationId, declaresQueryParameter } from '@/lib/api/transport';
import { isSameOrigin } from '@/lib/security/origin';
import { readSession } from '@/lib/session/require';

/**
 * The one endpoint a client component may read the platform through (M23.3).
 *
 * ── Why this exists at all ────────────────────────────────────────────────────────────
 *
 * §6.6 asks for server components on initial load *and* a client query library for interactive
 * lists — cursor pagination, refetch after a mutation, polling on active screens. All three need
 * a fetch the browser can make after hydration, and D187 forbids the only obvious one: the
 * browser may not call the gateway, because it holds no token and must never be given one.
 *
 * So the browser asks this route, and this route asks the platform with the session's credential.
 * The token stays on the server, the response comes back as JSON, and `connect-src 'self'` in the
 * CSP remains affordable because the browser still talks to exactly one origin.
 *
 * ── What it will and will not do ──────────────────────────────────────────────────────
 *
 * **Reads only.** `readOperation` resolves nothing but GETs (see that module for why), so this
 * cannot be turned into a capture or a refund by changing a path segment. Mutations stay in
 * Server Actions where the CSRF token and the confirmation dialogs are.
 *
 * **Never chooses the merchant or the mode.** Both come from the sealed session — `callAs` reads
 * `session.mode`, and the gateway derives the merchant from the JWT (M23.0). A client may name
 * an operation and its documented parameters, and nothing else. Sending `?mode=live` does not
 * work: `mode` is not a parameter of any operation, so it is rejected as unknown before a request
 * is built.
 *
 * **Parameters are checked against the contract, not passed through.** Anything not named in the
 * descriptor's `queryParameters` or its path template is a 400 here rather than a silently
 * ignored filter at the platform — the failure the transport already refuses to make, applied one
 * layer earlier so the client gets a useful message instead of a correct-looking unfiltered page.
 *
 * ── Same-origin, but no CSRF token ────────────────────────────────────────────────────
 *
 * A GET changes nothing, so there is no forgery to prevent and a synchronizer token would be
 * ceremony. The origin assertion stays because it costs one comparison and keeps this endpoint
 * from answering anything that is not this application — browsers already refuse to *read* a
 * cross-origin response without CORS headers, which this route never sends, so the check is
 * defence in depth rather than the only defence.
 *
 * ── Never cached ──────────────────────────────────────────────────────────────────────
 *
 * Every response is per-merchant and per-mode. `force-dynamic` plus `no-store` on the response is
 * the same reasoning as the transport's: a cached answer here would be another merchant's page.
 */
export const dynamic = 'force-dynamic';

interface ErrorBody {
  readonly error: {
    readonly type: string | undefined;
    readonly code: string | undefined;
    readonly message: string;
    /** Quoted in support requests — §6.6 requires it on every surfaced error. */
    readonly requestId: string | undefined;
  };
}

function errorResponse(status: number, body: ErrorBody): NextResponse {
  return NextResponse.json(body, { status, headers: { 'Cache-Control': 'no-store' } });
}

function problem(status: number, code: string, message: string): NextResponse {
  return errorResponse(status, {
    error: { type: undefined, code, message, requestId: undefined },
  });
}

export async function GET(
  request: NextRequest,
  context: { params: Promise<{ operation: string }> },
): Promise<NextResponse> {
  if (!isSameOrigin(request.headers)) {
    return problem(403, 'forbidden', 'This request did not come from the portal.');
  }

  const { operation: operationId } = await context.params;
  const operation = readOperation(operationId);
  if (!operation) {
    return problem(404, 'unknown_operation', `No readable operation named "${operationId}".`);
  }

  /*
   * `readSession` rather than `requireSession`: a guard that redirects is right for a page and
   * wrong for a fetch, where a 307 to an HTML login page arrives at code expecting JSON. 401 lets
   * the client layer do the one correct thing — send the user to sign in — deliberately.
   */
  const session = await readSession();
  if (!session) {
    return problem(401, 'unauthorized', 'This request needs a session.');
  }

  // `new URL(request.url)` rather than `request.nextUrl`: this handler needs the query string and
  // nothing else Next's parsed URL offers, and depending on the framework-specific property would
  // make the route untestable without constructing a `NextRequest` for a value it does not use.
  const { path, query, unknown } = splitParameters(new URL(request.url).searchParams, operation);
  if (unknown.length > 0) {
    return problem(
      400,
      'unknown_parameter',
      `${operationId} does not accept: ${unknown.join(', ')}.`,
    );
  }

  const missing = operation.pathParameters.filter((name) => path[name] === undefined);
  if (missing.length > 0) {
    return problem(400, 'missing_parameter', `${operationId} needs: ${missing.join(', ')}.`);
  }

  try {
    const data = await callAs<unknown>(operationId as OperationId, { session, path, query });
    return NextResponse.json(data, { headers: { 'Cache-Control': 'no-store' } });
  } catch (error) {
    if (error instanceof AuthenticationError) {
      // The session did not survive even `callAs`'s one refresh. Reported as 401 so the client
      // redirects rather than rendering an error the user cannot act on.
      return problem(401, 'unauthorized', 'This session is no longer valid.');
    }
    if (error instanceof PlatformError) {
      // Forwarded with the platform's own status, code and request id — §6.6's requirement that
      // an error surface starts a support conversation with an identifier.
      return errorResponse(error.status, {
        error: {
          type: error.type,
          code: error.code,
          message: error.message,
          requestId: error.requestId,
        },
      });
    }
    throw error;
  }
}

/**
 * Sorts the query string into path parameters, operation query parameters, and things the
 * operation does not accept.
 *
 * Driven entirely by the descriptor, so nothing here has to know what a payment is.
 */
function splitParameters(
  searchParams: URLSearchParams,
  operation: ReturnType<typeof readOperation> & object,
): {
  path: Record<string, string>;
  query: Record<string, string>;
  unknown: string[];
} {
  const path: Record<string, string> = {};
  const query: Record<string, string> = {};
  const unknown: string[] = [];

  for (const [name, value] of searchParams.entries()) {
    if (operation.pathParameters.includes(name)) {
      path[name] = value;
    } else if (declaresQueryParameter(operation.descriptor.queryParameters, name)) {
      // `declaresQueryParameter` rather than `includes`, so a map-valued parameter reaches the
      // platform in the spelling the contract documents for it — `metadata[order_id]=abc`. The
      // rule is still the descriptor's: a bracket suffix is accepted only on a declared base
      // name, so this widens what M23.6 can ask for without widening what may be asked.
      query[name] = value;
    } else {
      unknown.push(name);
    }
  }

  return { path, query, unknown };
}
