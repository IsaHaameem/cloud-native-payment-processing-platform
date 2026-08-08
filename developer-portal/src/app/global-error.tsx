'use client';

/**
 * The last resort (M23.1): an error thrown by the root layout itself, before any shell exists.
 *
 * It has to render its own `<html>` and `<body>` because the layout that would have provided
 * them is what failed. That also means it cannot use the providers, the fonts or the theme —
 * so the styling here is deliberately inline and self-sufficient rather than token-driven. A
 * page that depends on the thing that just broke is not a fallback.
 */
export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <html lang="en">
      <body
        style={{
          margin: 0,
          minHeight: '100dvh',
          display: 'grid',
          placeItems: 'center',
          background: '#0b0b12',
          color: '#fafafa',
          fontFamily: 'ui-sans-serif, system-ui, sans-serif',
          padding: '2rem',
        }}
      >
        <div style={{ maxWidth: '28rem', textAlign: 'center' }}>
          <h1 style={{ fontSize: '1.125rem', fontWeight: 600, margin: 0 }}>
            PaymentFlow could not start
          </h1>
          <p style={{ color: '#8a8a9e', fontSize: '0.875rem', marginTop: '0.5rem' }}>
            Something failed before the application could render.
            {error.digest ? ` Reference: ${error.digest}.` : ''}
          </p>
          <button
            onClick={reset}
            style={{
              marginTop: '1.5rem',
              padding: '0.5rem 1rem',
              borderRadius: '0.375rem',
              border: '1px solid #2a2a3a',
              background: '#16161f',
              color: '#fafafa',
              fontSize: '0.875rem',
              cursor: 'pointer',
            }}
          >
            Reload
          </button>
        </div>
      </body>
    </html>
  );
}
