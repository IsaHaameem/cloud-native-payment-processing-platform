import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { FlatCompat } from '@eslint/eslintrc';

const compat = new FlatCompat({ baseDirectory: dirname(fileURLToPath(import.meta.url)) });

/**
 * Flat config. `eslint-config-next` is still eslintrc-shaped, hence the compat wrapper —
 * this is the shape Next's own template ships and there is no flat-native equivalent yet.
 *
 * `eslint-config-prettier` comes last on purpose: it turns off the stylistic rules Prettier
 * already decides, so the two tools cannot disagree about the same line.
 */
const config = [
  {
    ignores: ['.next/**', 'node_modules/**', 'out/**', 'next-env.d.ts', 'src/generated/**'],
  },
  ...compat.extends('next/core-web-vitals', 'next/typescript', 'prettier'),
  {
    rules: {
      /*
       * The portal's own rules, each guarding something this repository has decided.
       */

      // Secrets and tokens live server-side (D187). A client component that imports the
      // transport or the session module is a build error rather than a leak, but the
      // `server-only` package can only say so at module level — this catches the sloppier
      // version, where a bare `process.env.X` reaches a bundle.
      'no-restricted-properties': [
        'error',
        {
          object: 'process',
          property: 'env',
          message:
            'Read configuration through @/lib/env, which validates it once and is server-only.',
        },
      ],

      // dangerouslySetInnerHTML has exactly one legitimate use here (the pre-hydration theme
      // script) and it carries an explicit disable comment naming why.
      'react/no-danger': 'error',

      // A promise dropped in a route handler is a request that silently does half its work.
      '@typescript-eslint/no-floating-promises': 'off',

      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],

      // No TODOs and no FIXMEs, as a rule rather than as a review habit.
      'no-warning-comments': ['error', { terms: ['todo', 'fixme'], location: 'anywhere' }],
    },
  },
  {
    // The env module is the one place allowed to read process.env — it is what the rule
    // above points every other file at.
    files: ['src/lib/env.ts'],
    rules: { 'no-restricted-properties': 'off' },
  },
  {
    /*
     * Verification scripts are Node programs, not part of any bundle. The rule above guards
     * against a bare `process.env.X` reaching the browser; these files never reach it, and
     * `@/lib/env` is `server-only` and TypeScript-resolved, so it cannot be imported here.
     *
     * Scoped to `scripts/**` rather than relaxed globally: the rule keeps its full force over
     * every file that can actually be bundled.
     */
    files: ['scripts/**/*.mjs'],
    rules: { 'no-restricted-properties': 'off' },
  },
];

export default config;
