/*
 * Removes the build output.
 *
 * A script rather than `rimraf`, because this package has a zero-runtime-dependency rule and
 * a payments SDK that drags a dependency tree into a developer's lockfile is a supply-chain
 * liability for every integrator (§7.2). The same reasoning applies one level up: a dev
 * dependency that exists to delete a directory is one more thing to audit, and `node:fs` has
 * done this natively since Node 14.
 */
import { rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const packageRoot = join(dirname(fileURLToPath(import.meta.url)), '..');

await rm(join(packageRoot, 'dist'), { recursive: true, force: true });
