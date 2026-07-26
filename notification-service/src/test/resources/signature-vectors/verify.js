/*
 * Independent Node.js implementation of the PaymentFlow webhook signature (M18.4, D105/D136).
 *
 * Written from the *specification* — not ported from the Java code — so that agreement
 * between the two is evidence the spec is implementable by a third party, which is the
 * only thing that actually protects an integrator. M22's Node SDK helper is expected to
 * consume this same vector file as its own fixture, so a divergence there fails loudly
 * rather than being discovered by a merchant.
 *
 * Run:  node verify.js
 * Exits non-zero if any vector disagrees.
 */
'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const doc = JSON.parse(fs.readFileSync(path.join(__dirname, 'webhook-signature-vectors.json'), 'utf8'));

/** signed_payload = "{timestamp}.{body}"; v1 = lowercase hex HMAC-SHA256(secret, signed_payload). */
function sign(secret, timestamp, body) {
  return crypto
    .createHmac('sha256', Buffer.from(secret, 'utf8'))
    .update(Buffer.from(`${timestamp}.${body}`, 'utf8'))
    .digest('hex');
}

/** What a merchant's own receiver would do: parse the header, recompute, compare in constant time. */
function verifyHeader(body, header, secret, nowEpochSeconds, toleranceSeconds) {
  const parts = header.split(',').map((p) => p.trim());
  let timestamp = null;
  const candidates = [];
  for (const part of parts) {
    const idx = part.indexOf('=');
    if (idx < 0) continue;
    const key = part.slice(0, idx);
    const value = part.slice(idx + 1);
    if (key === 't') timestamp = Number(value);
    else if (key === 'v1') candidates.push(value);
  }
  if (timestamp === null || candidates.length === 0) return false;
  if (Math.abs(nowEpochSeconds - timestamp) > toleranceSeconds) return false;

  const expected = Buffer.from(sign(secret, timestamp, body), 'utf8');
  return candidates.some((candidate) => {
    const actual = Buffer.from(candidate, 'utf8');
    return actual.length === expected.length && crypto.timingSafeEqual(actual, expected);
  });
}

let failures = 0;
for (const vector of doc.vectors) {
  const actual = sign(vector.secret, vector.timestamp, vector.body);
  const ok = actual === vector.expectedV1;
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${vector.name}`);
  if (!ok) {
    console.log(`      expected ${vector.expectedV1}`);
    console.log(`      actual   ${actual}`);
  }
}

// The replay window is the property the timestamp exists to enforce (D105) — assert it,
// rather than only asserting the hash matches.
const v = doc.vectors[0];
const header = `t=${v.timestamp},v1=${v.expectedV1}`;
const inWindow = verifyHeader(v.body, header, v.secret, v.timestamp + 60, 300);
const outOfWindow = verifyHeader(v.body, header, v.secret, v.timestamp + 8000, 300);
const wrongSecret = verifyHeader(v.body, header, 'whsec_NotTheRightSecretAtAll', v.timestamp, 300);
const tamperedBody = verifyHeader(`${v.body} `, header, v.secret, v.timestamp, 300);

for (const [name, expected, actual] of [
  ['accepts a signature inside the tolerance window', true, inWindow],
  ['rejects a replayed signature outside the window', false, outOfWindow],
  ['rejects a signature under the wrong secret', false, wrongSecret],
  ['rejects a tampered body', false, tamperedBody],
]) {
  const ok = expected === actual;
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}`);
}

console.log(failures === 0 ? '\nAll vectors agree (Node).' : `\n${failures} failure(s) (Node).`);
process.exit(failures === 0 ? 0 : 1);
