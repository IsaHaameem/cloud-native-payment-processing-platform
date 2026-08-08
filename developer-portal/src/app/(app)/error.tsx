'use client';

import * as React from 'react';

import { ErrorState } from '@/components/patterns/error-state';

/**
 * The error boundary for the authenticated shell (M23.1).
 *
 * Scoped to the route group on purpose: a failed page renders this *inside* the layout, so the
 * sidebar and header survive and the user can navigate away. A boundary at the root would blank
 * the whole application and leave them with the back button.
 *
 * `digest` is what Next puts on a server-side error in production, where the message is
 * redacted. Showing it is the difference between a support conversation that starts with an
 * identifier and one that starts with "it broke".
 */
export default function AppError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  React.useEffect(() => {
    // Deliberately the only console call in the portal: a boundary that swallows its error
    // silently makes the failure invisible in every environment at once.
    console.error(error);
  }, [error]);

  return (
    <ErrorState
      title="This page could not be loaded"
      description="The error has been logged. Try again, or pick another section from the sidebar."
      requestId={error.digest}
      onRetry={reset}
    />
  );
}
