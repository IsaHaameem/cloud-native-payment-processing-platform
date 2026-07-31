/*
 * Completes the dual ESM/CJS build.
 *
 * `tsc` emits `.js` files and nothing else; Node decides how to read them from the nearest
 * `package.json`'s `type` field, and this package's own says `module`. Without the two markers
 * written below, `dist/cjs/index.js` — which contains `require` and `module.exports` — would be
 * loaded as ESM and fail at the first line for every CommonJS consumer. The failure is not
 * subtle, but it only appears in a consuming project, which is exactly where a packaging bug
 * is most expensive to find (§7.3 makes "install the artefact and run it" a required test for
 * this reason).
 *
 * A ten-line script rather than a bundler. Nothing here needs bundling: the package is plain
 * TypeScript with no runtime dependencies, and adding tsup or rollup to write two JSON files
 * would be the largest dependency in the tree.
 */
import { writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const distRoot = join(dirname(fileURLToPath(import.meta.url)), '..', 'dist');

const markers = [
  ['esm', 'module'],
  ['cjs', 'commonjs'],
];

for (const [directory, type] of markers) {
  await writeFile(join(distRoot, directory, 'package.json'), `${JSON.stringify({ type }, null, 2)}\n`, 'utf8');
}
