import Link from 'next/link';

import { Wordmark } from '@/components/layout/logo';

/**
 * The shell for the pages you can reach without a session (M23.2; two pages since M23.2a).
 *
 * A separate route group from `(app)` because it is the *absence* of the app shell that matters:
 * no sidebar, no header, no mode banner. A sign-in page framed by the chrome of the application
 * it guards suggests the application is already open.
 *
 * Centred, narrow, and quiet. The only decoration is a single soft accent glow behind the card —
 * the one place in the portal that gets one, because this is the one page with nothing else on
 * it.
 *
 * The wordmark links home. Without it these two pages are the only surfaces in the product with
 * no way back out, which is the state a visitor who clicked "Sign in" from the landing page to
 * look around lands in.
 */
export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="relative flex min-h-dvh flex-col items-center justify-center overflow-hidden bg-canvas px-4 py-12">
      <div
        aria-hidden
        className="pointer-events-none absolute top-1/2 left-1/2 size-[560px] -translate-x-1/2 -translate-y-[65%] rounded-full opacity-[0.07] blur-[120px]"
        style={{ background: 'var(--accent)' }}
      />

      <div id="main" className="relative w-full max-w-[380px]">
        <div className="mb-7 flex justify-center">
          <Link
            href="/"
            aria-label="PaymentFlow home"
            className="rounded-md opacity-90 transition-opacity duration-(--duration-fast) hover:opacity-100"
          >
            <Wordmark />
          </Link>
        </div>
        {children}
      </div>
    </div>
  );
}
