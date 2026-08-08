import Link from 'next/link';

import { Wordmark } from '@/components/layout/logo';
import { Button } from '@/components/ui/button';

export default function NotFound() {
  return (
    <div className="relative grid min-h-dvh place-items-center overflow-hidden px-6">
      <div aria-hidden className="bg-grid bg-grid-fade absolute inset-0 -z-10" />
      <div className="text-center">
        <Wordmark className="justify-center" />
        <p className="mt-10 text-title-1 font-[510] tracking-[-0.88px] text-fg">Page not found</p>
        <p className="mt-2 text-body text-fg-subtle">
          The page you asked for does not exist, or has moved.
        </p>
        <Button variant="secondary" asChild className="mt-8">
          <Link href="/">Back to start</Link>
        </Button>
      </div>
    </div>
  );
}
