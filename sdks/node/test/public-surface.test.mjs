/*
 * What this package promises an integrator, and what it deliberately does not.
 *
 * Run against `dist/`, not `src/`. The thing a user installs is the built output, and a
 * packaging mistake — a wrong `exports` map, a missing declaration file, an ESM/CJS marker
 * that never got written — is invisible from the source tree and total from a consumer's.
 * §7.3 makes "consume the built artefact" a required test for exactly that reason.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const packageRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);

const esm = await import('../dist/esm/index.js');

test('the public surface is exactly what index.ts names', () => {
  // An exact list, not a subset. The point of this assertion is to make growing the public
  // API a deliberate edit here rather than a side effect of an export added somewhere else —
  // and in particular to make re-exporting the generated tree impossible to do by accident.
  assert.deepEqual(Object.keys(esm).sort(), [
    'API_VERSION',
    'ApiConnectionError',
    'ApiError',
    'AuthenticationError',
    'DEFAULT_BASE_URL',
    'IdempotencyError',
    'InvalidRequestError',
    'PaymentFlow',
    'PaymentFlowConfigurationError',
    'PaymentFlowError',
    'PermissionError',
    'RateLimitError',
    'USER_AGENT',
    'VERSION',
  ]);
});

test('the error hierarchy is exported as classes an integrator can branch on', () => {
  // Every one of these has to be a real constructor reachable from the package root, because
  // the way a caller distinguishes "fix your key" from "retry later" is `instanceof`. A
  // type-only export would type-check in their editor and be `undefined` at runtime.
  for (const name of [
    'PaymentFlowError',
    'AuthenticationError',
    'PermissionError',
    'InvalidRequestError',
    'IdempotencyError',
    'RateLimitError',
    'ApiConnectionError',
    'ApiError',
  ]) {
    assert.equal(typeof esm[name], 'function', `${name} is a class`);
  }

  // And all of them narrow from the base, so `catch (e) { if (e instanceof PaymentFlowError) }`
  // is a complete handler rather than one that misses six cases.
  for (const name of ['AuthenticationError', 'RateLimitError', 'ApiConnectionError', 'ApiError']) {
    const error = new esm[name]('boom');
    assert.ok(error instanceof esm.PaymentFlowError, `${name} extends PaymentFlowError`);
    assert.equal(error.name, name, 'the class name survives into `error.name`');
  }
});

test('every resource namespace named by the milestone exists on a client', () => {
  const client = new esm.PaymentFlow({ apiKey: 'sk_test_surface' });

  // The eleven the milestone specifies, by name. A namespace renamed or dropped is a breaking
  // change to every integrator at once, and is not the sort of thing to notice in review.
  for (const name of [
    'payments',
    'refunds',
    'balance',
    'balanceTransactions',
    'events',
    'analytics',
    'requestLogs',
    'usage',
    'webhookEndpoints',
    'webhookDeliveries',
    'testHelpers',
  ]) {
    assert.equal(typeof client[name], 'object', `client.${name} exists`);
  }
});

test('nothing generated leaks into the public surface', async () => {
  const operations = await import('../dist/esm/generated/operations.js');
  const models = await import('../dist/esm/generated/models.js');
  const contract = await import('../dist/esm/generated/contract.js');

  const generatedNames = new Set([
    ...Object.keys(operations),
    ...Object.keys(models),
    ...Object.keys(contract),
  ]);

  // The generated tree is real and non-empty — asserted first, so this test cannot pass by
  // comparing the public surface against nothing.
  assert.ok(generatedNames.has('OPERATIONS'), 'the generated operations exist');
  assert.ok(generatedNames.size > 3, 'the generated tree exports more than a couple of names');

  // And none of it is reachable from the package root. `API_VERSION` and `DEFAULT_BASE_URL`
  // share a name with a generated constant on purpose: index.ts re-exports the *values* under
  // names it chose, which is the difference between publishing a fact and publishing a
  // generator's output.
  const chosen = new Set(['API_VERSION', 'DEFAULT_BASE_URL']);
  for (const name of Object.keys(esm)) {
    if (chosen.has(name)) {
      continue;
    }
    assert.equal(generatedNames.has(name), false, `${name} is exported straight from the generated tree`);
  }
});

test('the CommonJS build loads and agrees with the ESM build', () => {
  // The dual build's whole failure mode: `dist/cjs` contains `require`, and without the
  // `{"type":"commonjs"}` marker scripts/finalize-dual-build.mjs writes, Node reads it as ESM
  // and throws on the first line. That failure never appears in this repository — only in a
  // consuming project — unless something loads it the way a consumer would.
  const cjs = require('../dist/cjs/index.js');

  assert.equal(cjs.VERSION, esm.VERSION);
  assert.equal(cjs.API_VERSION, esm.API_VERSION);
  assert.equal(cjs.DEFAULT_BASE_URL, esm.DEFAULT_BASE_URL);
});

test('both builds are declared to Node with the module system they actually use', async () => {
  const esmMarker = JSON.parse(await readFile(join(packageRoot, 'dist/esm/package.json'), 'utf8'));
  const cjsMarker = JSON.parse(await readFile(join(packageRoot, 'dist/cjs/package.json'), 'utf8'));

  assert.equal(esmMarker.type, 'module');
  assert.equal(cjsMarker.type, 'commonjs');
});

test('the package version and the SDK version constant agree', async () => {
  const manifest = JSON.parse(await readFile(join(packageRoot, 'package.json'), 'utf8'));

  // Two places, one fact. npm reads the manifest, the User-Agent reads the constant, and a
  // release where they disagree publishes an SDK that misreports itself in every request log.
  assert.equal(esm.VERSION, manifest.version);
  assert.ok(esm.USER_AGENT.startsWith(`paymentflow-node/${manifest.version} `));
});

test('type declarations are emitted for the published entry point', async () => {
  const declaration = await readFile(join(packageRoot, 'dist/esm/index.d.ts'), 'utf8');

  // The types are this package's product. A build that emitted JavaScript and no `.d.ts`
  // installs cleanly and gives a TypeScript user nothing at all.
  assert.match(declaration, /API_VERSION/);
  assert.match(declaration, /USER_AGENT/);
  assert.match(declaration, /export \{ PaymentFlow \}/);
});

test('the generated models reach users by a curated list, never by a wildcard', async () => {
  const declaration = await readFile(join(packageRoot, 'dist/esm/index.d.ts'), 'utf8');

  // The architecture's rule is that generated code is an internal implementation detail. Taken
  // literally that would make the SDK unusable — a caller has to be able to name the object a
  // method returns — so the resolution is that the *types* are re-exported one by one, from a
  // list a person maintains, and the *values* are not re-exported at all. A wildcard would
  // quietly hand the generator authority over this package's public API, so that a schema
  // renamed inside a Java service becomes a breaking change nobody reviewed.
  // Anchored to the start of a line: this file's own prose quotes the wildcard form as the
  // thing it exists to avoid, and an unanchored pattern matched the explanation.
  assert.doesNotMatch(declaration, /^export\s+\*\s+from\s+['"]\.\/generated/m);
  assert.match(declaration, /PaymentResponse/, 'the response models are nameable');

  // The request models are deliberately absent: what a caller passes is the hand-written
  // parameter type, which states requirements the document understates (D170).
  assert.doesNotMatch(declaration, /\bCreatePaymentRequest\b/);
});
