/*
 * Runs the suite by handing `node --test` an explicit list of files.
 *
 * The obvious spellings are not portable across the versions this package supports, and the
 * two that exist fail in opposite directions:
 *
 *   node --test test/                 works on Node 20; on Node 22+ a directory argument is
 *                                     resolved as a module to execute, so it dies with
 *                                     MODULE_NOT_FOUND before a single test runs.
 *   node --test "test/**\/*.test.mjs" works on Node 21+, which expands globs itself; on Node 20
 *                                     the runner has no glob support, no shell expands a quoted
 *                                     pattern either, and it is looked up as a literal path:
 *                                     `Could not find '...'`, exit 1.
 *
 * An explicit file list is the one form every version accepts, and CI pins Node 20 on purpose
 * (see .github/workflows/ci.yml) while contributors are typically on something much newer — so
 * "works on my machine" and "works in CI" were, for this one line, mutually exclusive. That is
 * what made the Node job red for the whole of M22.
 *
 * The list is enumerated here rather than written into package.json because a hardcoded list is
 * a suite that silently stops covering a file the day someone adds one — which is precisely the
 * failure mode the repository's testing rules forbid: a test must not be able to pass by failing
 * to do the thing it claims to do. For the same reason, finding no files is a failure, not an
 * empty green run.
 *
 * A script rather than a dev dependency, per this package's zero-dependency rule (§7.2) and the
 * same reasoning as scripts/clean.mjs.
 */
import { readdir } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const packageRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const testDir = join(packageRoot, 'test');

const files = (await readdir(testDir))
    .filter((name) => name.endsWith('.test.mjs'))
    .sort()
    // Relative to the package root, and always with forward slashes: the runner reports the
    // path it was given, so the output reads the same on every platform.
    .map((name) => `test/${name}`);

if (files.length === 0) {
    console.error(`No *.test.mjs files found in ${testDir} — refusing to report an empty suite as a pass.`);
    process.exit(1);
}

const result = spawnSync(process.execPath, ['--test', ...files], {
    cwd: packageRoot,
    stdio: 'inherit',
});

if (result.error) {
    console.error(result.error);
    process.exit(1);
}

// A signal-terminated child reports a null status; treat anything that is not a clean 0 as a
// failure rather than letting `undefined` become a green exit.
process.exit(result.status ?? 1);
