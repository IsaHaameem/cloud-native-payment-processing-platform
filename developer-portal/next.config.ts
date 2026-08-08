import type { NextConfig } from 'next';

/**
 * Security headers applied to every response.
 *
 * The CSP is deliberately strict and deliberately *incomplete* at M23.1: it is
 * report-only until M23.9, and `script-src` still admits `'unsafe-inline'` because the
 * nonce plumbing belongs to M23.2's middleware, which does not exist yet. Shipping a
 * placeholder that claims to be enforced would be worse than shipping one that says what
 * it is — so it says what it is, here, in the file that would otherwise be read as a
 * finished control.
 *
 * `connect-src 'self'` is affordable only because of D187: the browser talks to this
 * server and nothing else, and this server talks to the gateway. A design where the
 * browser called the gateway directly would have to widen it to the API origin.
 */
const securityHeaders = [
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  { key: 'X-Frame-Options', value: 'DENY' },
  { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
  {
    key: 'Permissions-Policy',
    value: 'geolocation=(), camera=(), microphone=(), payment=()',
  },
  {
    key: 'Content-Security-Policy-Report-Only',
    value: [
      "default-src 'self'",
      "script-src 'self' 'unsafe-inline'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data:",
      "font-src 'self'",
      "connect-src 'self'",
      "frame-ancestors 'none'",
      "base-uri 'none'",
      "form-action 'self'",
      "object-src 'none'",
    ].join('; '),
  },
];

const nextConfig: NextConfig = {
  /*
   * A self-contained server bundle, which is what the Dockerfile's runtime stage copies.
   * Without it the image would need the whole node_modules tree.
   */
  output: 'standalone',

  /*
   * The portal is one app inside a monorepo whose root holds a Gradle build. Next infers the
   * workspace root from lockfiles and would otherwise pick the repository root, tracing files
   * it has no business shipping.
   */
  outputFileTracingRoot: __dirname,

  reactStrictMode: true,
  poweredByHeader: false,

  typescript: {
    // The build must not be the place a type error is discovered *and forgiven*.
    ignoreBuildErrors: false,
  },
  eslint: {
    ignoreDuringBuilds: false,
  },

  async headers() {
    return [{ source: '/:path*', headers: securityHeaders }];
  },
};

export default nextConfig;
