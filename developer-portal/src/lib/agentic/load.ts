import 'server-only';

import { ConnectionError, PlatformError } from '@/lib/api/errors';

/**
 * The result of loading agentic data in a Server Component: the value, or a structured error the
 * page renders inline.
 *
 * The agentic screens must fail the way the rest of the portal does — a real platform error code
 * and a request id to quote, never a bare "something went wrong". This wraps a load so a page
 * body stays a simple branch on `data` vs `error`.
 */
export type AgenticLoad<T> =
  | { readonly data: T; readonly error?: undefined }
  | {
      readonly data?: undefined;
      readonly error: {
        readonly message: string;
        readonly code: string | undefined;
        readonly requestId: string | undefined;
        /** True when the agentic service itself could not be reached, vs. a normal 4xx/5xx. */
        readonly unreachable: boolean;
      };
    };

export async function loadAgentic<T>(run: () => Promise<T>): Promise<AgenticLoad<T>> {
  try {
    return { data: await run() };
  } catch (error) {
    if (error instanceof ConnectionError) {
      return {
        error: {
          message:
            'The agentic commerce service is not responding. It runs alongside the platform on ' +
            'its own port; check that it is up.',
          code: 'agentic_unreachable',
          requestId: undefined,
          unreachable: true,
        },
      };
    }
    if (error instanceof PlatformError) {
      return {
        error: {
          message: error.message,
          code: error.code,
          requestId: error.requestId,
          unreachable: false,
        },
      };
    }
    throw error;
  }
}
