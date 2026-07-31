/*
 * The Node half of the cross-language parity check.
 *
 * `sdks/shared/fixtures` is written by the same generator that writes both SDKs' models, and
 * both SDKs assert against it. That is what makes "the two languages describe the same
 * contract" a test rather than a claim: Python runs the mirror of this file
 * (`sdks/python/tests/test_parity.py`), against the same JSON, asserting the same facts.
 *
 * Asserting against the fixture rather than against Python directly is deliberate. A test
 * that shelled out to the other language would need both toolchains present to run either
 * SDK's suite, which is the prerequisite D136 keeps out of this repository.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

import { API_VERSION, DEFAULT_BASE_URL } from '../dist/esm/index.js';
import { OPERATIONS } from '../dist/esm/generated/operations.js';
import * as models from '../dist/esm/generated/models.js';

const fixtures = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'shared', 'fixtures');

const read = async (name) => JSON.parse(await readFile(join(fixtures, name), 'utf8'));

const contract = await read('contract.json');
const enums = await read('enums.json');
const modelFixture = await read('models.json');
const operationFixture = await read('operations.json');

test('the fixtures describe a contract, not an empty file', () => {
  // A parity suite whose fixtures silently emptied would pass every assertion below by
  // iterating over nothing. Checking the counts first is what stops this file being able to
  // pass by failing to do its job.
  assert.ok(contract.operationCount > 0);
  assert.ok(contract.modelCount > 0);
  assert.ok(contract.enumCount > 0);
  assert.equal(Object.keys(operationFixture).length, contract.operationCount);
  assert.equal(Object.keys(modelFixture).length, contract.modelCount);
  assert.equal(Object.keys(enums).length, contract.enumCount);
});

test('the SDK reports the API revision the contract names', () => {
  assert.equal(API_VERSION, contract.apiVersion);
  assert.equal(DEFAULT_BASE_URL, contract.baseUrl);
});

test('every published operation is addressable, with the same method, path and shape', () => {
  assert.deepEqual(Object.keys(OPERATIONS).sort(), Object.keys(operationFixture).sort());

  for (const [id, expected] of Object.entries(operationFixture)) {
    const actual = OPERATIONS[id];
    assert.equal(actual.method, expected.method, `${id} method`);
    assert.equal(actual.path, expected.path, `${id} path`);
    assert.equal(actual.tag, expected.tag, `${id} tag`);
    assert.equal(actual.successStatus, expected.successStatus, `${id} success status`);
    assert.equal(actual.hasRequestBody, expected.hasRequestBody, `${id} request body`);
    assert.deepEqual([...actual.queryParameters], expected.queryParameters, `${id} query parameters`);
  }
});

/*
 * `models.json` is checked field-for-field on the Python side, not here, and the asymmetry is
 * a property of the languages rather than a gap. A TypeScript interface is erased at compile
 * time — there is nothing at runtime to compare a field list against — so TypeScript's half of
 * the model check is `tsc` itself, which fails if a generated interface is malformed or names
 * a type that does not exist. Both emitters read one intermediate representation
 * (`SdkSpec`), so a field list that is right in Python is right here by construction; what
 * could still differ between the languages is what is *exported* and how it is *spelled*, and
 * that is what the assertions below and in `public-surface.test.mjs` cover.
 */
test('every enum vocabulary matches the contract, value for value and in order', () => {
  for (const [name, values] of Object.entries(enums)) {
    // `PaymentResponseMode` -> `PAYMENT_RESPONSE_MODE_VALUES`, the same derivation the Python
    // emitter uses, so a rename in one language would fail the other language's suite too.
    const constant = `${name.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase()}_VALUES`;
    assert.ok(constant in models, `${constant} is exported`);
    assert.deepEqual([...models[constant]], values, `${name} vocabulary`);
  }
});

test('the error classification is a vocabulary both SDKs can map to exception classes', () => {
  // The single most load-bearing enum in either SDK: §7.1's typed error hierarchy branches on
  // it. M22.0 published it as a real enum so this could be checked rather than transcribed.
  assert.deepEqual(enums.ApiErrorType, [
    'authentication_error',
    'permission_error',
    'invalid_request_error',
    'idempotency_error',
    'rate_limit_error',
    'api_error',
  ]);
});
