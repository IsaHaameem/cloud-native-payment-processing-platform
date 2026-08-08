/**
 * The name of the CSRF form field, in a module a client component may import (M23.2).
 *
 * `csrf.ts` is `server-only` — it reads and writes the cookie, and marking it so is what makes a
 * client import of the token machinery a build error rather than a leak. But the *field name* is
 * not a secret, and the form that carries it is a client component, so the constant lives here
 * and `csrf.ts` re-exports it. One spelling, two importers, no way for them to disagree.
 */
export const CSRF_FIELD = 'csrfToken';
