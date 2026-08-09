import 'server-only';

/**
 * The portal's configuration, validated once at module load (M23.1).
 *
 * <p>Validated rather than read, and validated *eagerly*, because the failure mode otherwise
 * is the worst kind: a portal that starts happily without a session secret, generates one per
 * process, and logs every user out on each deploy — with no error anywhere to explain it. A
 * missing value should stop the server on the line that needed it, at boot, not surface as a
 * mystery six hours later.
 *
 * `PORTAL_SESSION_SECRET` is read here and used by nothing yet: the session module is M23.2.
 * It is validated now anyway, because the point of this file is that the whole configuration
 * is checked in one place at one time — a value that starts being required in a later
 * milestone would otherwise be discovered by a deploy rather than by a boot.
 */

const isProduction = process.env.NODE_ENV === 'production';

/**
 * `next build` runs with `NODE_ENV=production` and imports every route module to collect page
 * data — so without this, building the image would require the *production* session secret to be
 * present at build time, and a secret available to a build is a secret in a layer.
 *
 * The distinction that matters is boot versus build. This file's promise is that a misconfigured
 * server refuses to start; a build is not a server and holds no configuration. `NEXT_PHASE` is
 * how Next.js says which one is running.
 */
const isBuildPhase = process.env.NEXT_PHASE === 'phase-production-build';

function required(name: string, fallback?: string): string {
  const value = process.env[name] ?? fallback;
  if (value === undefined || value.trim() === '') {
    throw new Error(
      `${name} is not set. The portal cannot start without it; see developer-portal/README.md.`,
    );
  }
  return value;
}

function url(name: string, fallback?: string): string {
  const value = required(name, fallback);
  try {
    // Throws on anything that is not an absolute URL, which is the mistake worth catching:
    // a host with no scheme silently becomes a relative fetch against the portal itself.
    return new URL(value).toString().replace(/\/$/, '');
  } catch {
    throw new Error(`${name} must be an absolute URL, but was "${value}".`);
  }
}

/**
 * A comma-separated list of absolute origins, normalised the same way {@link url} normalises one.
 *
 * Absent and empty both mean "none". Whitespace around entries is tolerated because this value is
 * typed into a compose file or a deployment console by a human.
 */
function originList(name: string): readonly string[] {
  const raw = process.env[name];
  if (raw === undefined || raw.trim() === '') return [];

  return raw
    .split(',')
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0)
    .map((entry) => {
      try {
        return new URL(entry).origin;
      } catch {
        throw new Error(`${name} contains "${entry}", which is not an absolute URL.`);
      }
    });
}

/**
 * A 32-byte secret, base64 or hex. Only enforced in production: local development would
 * otherwise need a secret before it could render a page, and the value protects nothing on a
 * developer's machine. The asymmetry is deliberate and is why the check names the environment.
 */
function sessionSecret(): string {
  const value = process.env.PORTAL_SESSION_SECRET;
  if (value === undefined || value.trim() === '') {
    if (isProduction && !isBuildPhase) {
      throw new Error(
        'PORTAL_SESSION_SECRET is not set. Refusing to start: a portal that invents its own ' +
          'session key signs every user out on each deploy.',
      );
    }
    return 'development-only-session-secret-not-for-any-deployed-environment';
  }
  if (value.length < 32) {
    throw new Error('PORTAL_SESSION_SECRET must be at least 32 characters.');
  }
  return value;
}

export const env = {
  /** Where the gateway lives. Every platform call this server makes goes here. */
  gatewayUrl: url('PF_GATEWAY_URL', 'http://localhost:8080'),

  /** This portal's own origin, used for absolute links and for the CSRF origin check (M23.2). */
  publicOrigin: url('PORTAL_PUBLIC_ORIGIN', 'http://localhost:3000'),

  /**
   * Further origins this same portal is legitimately served on (M23.2b).
   *
   * Comma-separated and empty by default, because a deployment with one origin should configure
   * one origin. It exists for the deployments that genuinely have more than one — a vanity
   * domain beside the canonical one, or a staging host — where the alternative is that the
   * origin check refuses real users and reports it as an expired form.
   *
   * Each entry is validated as an absolute URL at boot, for the same reason `PORTAL_PUBLIC_ORIGIN`
   * is: a typo here does not weaken the check, it silently removes an origin from it, and a
   * mistake that makes a security control *stricter than intended* still breaks the product.
   */
  additionalOrigins: originList('PORTAL_ADDITIONAL_ORIGINS'),

  /** Encrypts the session cookie. Consumed by M23.2; validated here (see above). */
  sessionSecret: sessionSecret(),

  isProduction,
} as const;

export type Env = typeof env;
