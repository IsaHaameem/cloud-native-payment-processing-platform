/*
 * What actually ships (M22.4).
 *
 * Everything here asks a question that can only be answered about the *package*, not about the
 * source: what does `npm pack` put in the tarball, what do the two entry points resolve to,
 * and can a bundler drop what an application does not use. None of those failures are visible
 * from `src/`, and all of them are total from a consumer's side — a wrong `exports` map is an
 * `ERR_PACKAGE_PATH_NOT_EXPORTED` on their first import, not a warning on ours.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { readFile, stat } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const packageRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const manifest = JSON.parse(await readFile(join(packageRoot, 'package.json'), 'utf8'));

/** `npm pack --dry-run --json` reports the exact file list a publish would upload. */
function packedFiles() {
  const output = execFileSync('npm', ['pack', '--dry-run', '--json'], {
    cwd: packageRoot,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    shell: process.platform === 'win32',
  });
  const [result] = JSON.parse(output);
  return { result, paths: result.files.map((file) => file.path.replace(/\\/g, '/')) };
}

// ── The manifest ────────────────────────────────────────────────────────────────────────

test('the package is still marked private, so a publish cannot happen by accident', () => {
  // Publishing to a public registry is irreversible and claims a name. It needs an explicit
  // decision, not a green build. `npm publish` refuses outright while this is true.
  assert.equal(manifest.private, true);
});

test('the manifest declares both module systems and points each at a real file', async () => {
  assert.equal(manifest.type, 'module');
  assert.equal(manifest.exports['.'].types, './dist/esm/index.d.ts');
  assert.equal(manifest.exports['.'].import, './dist/esm/index.js');
  assert.equal(manifest.exports['.'].require, './dist/cjs/index.js');

  for (const target of Object.values(manifest.exports['.'])) {
    const file = await stat(join(packageRoot, target));
    assert.ok(file.isFile(), `${target} exists`);
    assert.ok(file.size > 0, `${target} is not empty`);
  }
});

test('the `types` condition comes first in the exports map', () => {
  // Conditional exports are matched in declaration order, so a `types` entry after `import`
  // is never reached and TypeScript silently falls back to `any` for the whole package.
  assert.equal(Object.keys(manifest.exports['.'])[0], 'types');
});

test('the package declares itself side-effect free, and genuinely is', async () => {
  // The claim a bundler acts on: without it, importing `constructEvent` retains the transport,
  // the eleven resource namespaces and every generated model. With it, and nothing running at
  // import time, an application carries only what it references.
  assert.equal(manifest.sideEffects, false);

  // The claim, checked. Every emitted module must be declarations only — no top-level call
  // that does work, and no mutation of anything outside itself.
  const entry = await readFile(join(packageRoot, 'dist/esm/index.js'), 'utf8');
  for (const line of entry.split('\n')) {
    const code = line.trim();
    if (code === '' || code.startsWith('//') || code.startsWith('/*') || code.startsWith('*')) continue;
    assert.match(code, /^(export|import)\b/, `index.js only re-exports; found: ${code}`);
  }
});

test('the package has no runtime dependencies', () => {
  // A payments SDK that drags in a transitive tree is a supply-chain liability for every
  // integrator. Zero is the design, and this is what keeps it zero.
  assert.deepEqual(manifest.dependencies, {});
});

// ── The tarball ─────────────────────────────────────────────────────────────────────────

test('npm pack succeeds and ships the built output', () => {
  const { paths } = packedFiles();

  assert.ok(paths.includes('dist/esm/index.js'), 'the ESM entry point ships');
  assert.ok(paths.includes('dist/cjs/index.js'), 'the CommonJS entry point ships');
  assert.ok(paths.includes('dist/esm/index.d.ts'), 'the type declarations ship');
  assert.ok(paths.includes('package.json'), 'the manifest ships');
  assert.ok(paths.includes('README.md'), 'the README ships — npm renders it as the package page');
});

test('the tarball carries no sources, tests, examples or configuration', () => {
  const { paths } = packedFiles();

  // `files: ["dist"]` is an allow-list, so this is really a test that nobody has widened it.
  // Shipping `src/` doubles the install size and invites a consumer to import past the public
  // entry point, at which point the package's API is whatever they happened to reach for.
  const unwanted = paths.filter((path) =>
    /^(src|test|examples|scripts)\//.test(path) || /^tsconfig/.test(path) || path.endsWith('.tsbuildinfo'),
  );
  assert.deepEqual(unwanted, [], 'only dist/, package.json and README.md ship');
});

test('the two module-system markers ship with the build they mark', () => {
  const { paths } = packedFiles();

  // dist/cjs contains `require` calls, and without its {"type":"commonjs"} marker Node reads
  // it as ESM — because the root manifest says `"type": "module"` — and throws on line one.
  // The marker existing locally is not enough; it has to be inside the tarball.
  assert.ok(paths.includes('dist/esm/package.json'), 'the ESM marker ships');
  assert.ok(paths.includes('dist/cjs/package.json'), 'the CommonJS marker ships');
});

// ── The entry points, loaded the way a consumer loads them ──────────────────────────────

test('the ESM entry point imports and exposes the client', async () => {
  const esm = await import('../dist/esm/index.js');
  assert.equal(typeof esm.PaymentFlow, 'function');
  assert.equal(typeof esm.constructEvent, 'function');
});

test('the CommonJS entry point requires and agrees with the ESM one', async () => {
  const cjs = require('../dist/cjs/index.js');
  const esm = await import('../dist/esm/index.js');

  assert.deepEqual(Object.keys(cjs).sort(), Object.keys(esm).sort(), 'the same surface either way');
  assert.equal(typeof cjs.PaymentFlow, 'function');

  // And it works, not merely loads: a build that emitted ESM into dist/cjs would import
  // cleanly here and fail the moment anything ran.
  const client = new cjs.PaymentFlow({ apiKey: 'sk_test_cjs' });
  assert.equal(typeof client.payments.create, 'function');
  assert.equal(cjs.VERSION, esm.VERSION);
});

test('both builds verify a signature identically', async () => {
  const cjs = require('../dist/cjs/index.js');
  const esm = await import('../dist/esm/index.js');

  const secret = 'whsec_TestVectorSecretDoNotUseInProduction';
  const body = '{"id":"evt_1","object":"event","type":"payment.captured","data":{"object":{}}}';
  const now = 1785758400;
  const header = esm.signatureHeaderFor(secret, now, body);

  // The crypto path in particular: `node:crypto` is imported differently in the two outputs,
  // and a dual build that got that wrong would fail only here.
  assert.deepEqual(
    cjs.constructEvent(body, header, secret, { nowEpochSeconds: now }),
    esm.constructEvent(body, header, secret, { nowEpochSeconds: now }),
  );
});

test('the declarations cover the whole public surface, not just its values', async () => {
  const declaration = await readFile(join(packageRoot, 'dist/esm/index.d.ts'), 'utf8');

  // The types are half of what this package delivers. A build that emitted JavaScript and an
  // incomplete .d.ts installs cleanly and gives a TypeScript user a worse experience than no
  // types at all, because the gaps are silent.
  for (const name of [
    'PaymentFlow',
    'PaymentFlowOptions',
    'RequestOptions',
    'ResponseMeta',
    'CursorPage',
    'OffsetPage',
    'WebhookEvent',
    'PaymentCreateParams',
    'PaymentResponse',
    'RateLimitError',
    'WebhookTimestampError',
  ]) {
    assert.match(declaration, new RegExp(`\\b${name}\\b`), `${name} is declared`);
  }

  // Declaration maps let an editor jump from a type to the source that defined it.
  const map = await stat(join(packageRoot, 'dist/esm/index.d.ts.map'));
  assert.ok(map.isFile());
});
