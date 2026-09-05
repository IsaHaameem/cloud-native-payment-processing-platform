import { fileURLToPath } from 'node:url';

import { defineConfig } from 'vitest/config';

/**
 * The portal's unit and integration suite (M23.2).
 *
 * ── Why a runner at all, when the repository prefers zero dependencies ────────────────
 *
 * `sdks/node` runs on `node --test` with no dev dependency, and that is the right call *there*:
 * it tests plain ESM with no path aliases and no framework. This package is TypeScript with `@/`
 * aliases resolved from `tsconfig.json`, so a bare Node runner needs a loader and an alias
 * resolver before it can import a single file under test — at which point the "zero dependency"
 * saving is a hand-written loader nobody wants to own.
 *
 * ── `alias` mirrors tsconfig deliberately ─────────────────────────────────────────────
 *
 * One entry, matching `paths` in `tsconfig.json`. If they ever disagree the tests import
 * different modules than the application does, which is the worst possible kind of green.
 *
 * ── `server-only` is stubbed ──────────────────────────────────────────────────────────
 *
 * Nearly every module under test imports `server-only`, whose whole purpose is to throw when it
 * is pulled into a client bundle. Under Vitest there is no bundle and no client, so the import is
 * aliased to an empty module. This does not weaken the guarantee it provides: that guarantee is
 * enforced by `next build`, which the `verify` script runs, and which fails on a real violation.
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      'server-only': fileURLToPath(new URL('./test/stubs/server-only.ts', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    /*
     * `.mjs` is included for exactly one file. `test/stub-reset.test.mjs` covers
     * `scripts/lib/stub-reset.mjs`, which is harness code for the browser suites rather than
     * application code — plain Node ESM, like every other file under `scripts/`. Writing its
     * test in TypeScript would mean hand-writing a declaration file for a module the
     * application never imports, and a hand-written declaration is one more thing that can
     * drift from the code it describes.
     */
    include: ['test/**/*.test.ts', 'test/**/*.test.mjs'],
    // Each file gets a fresh module registry, which the coordinator and throttle tests depend on:
    // both assert on module-level state, and a shared registry would let one file's leftovers
    // decide another file's result.
    isolate: true,
    restoreMocks: true,
  },
});
