import 'server-only';

import { env } from '@/lib/env';

/**
 * Sealing and unsealing the session cookie (M23.2, D196).
 *
 * ── Encrypted, not signed ─────────────────────────────────────────────────────────────
 *
 * The cookie carries an access token and a refresh token. Signing alone would make it
 * *tamper-evident* while leaving both readable by anyone who can see the cookie — a browser
 * extension, a crash dump, a proxy log, a screenshot of devtools. Authenticated encryption makes
 * it both unreadable and unforgeable, and AES-GCM gives that in one primitive.
 *
 * ── Web Crypto rather than `node:crypto` ──────────────────────────────────────────────
 *
 * `middleware.ts` is the portal's single refresh point, so it has to unseal and re-seal. Web
 * Crypto is the one API available in every runtime Next.js may place that code in, and using it
 * everywhere means the session format cannot become something only half the server can read.
 *
 * ── The version is authenticated, not merely written ──────────────────────────────────
 *
 * `v1` is both a prefix and the GCM additional-authenticated-data. An attacker who rewrites the
 * prefix to a format with weaker rules does not get a cookie that decrypts under the old rules —
 * they get a decrypt failure, because the tag covers the version they changed. A version that is
 * only a prefix is a downgrade waiting to happen.
 *
 * ── Key derivation ────────────────────────────────────────────────────────────────────
 *
 * HKDF-SHA256 over `PORTAL_SESSION_SECRET` with a fixed info string, rather than using the secret
 * as key material directly. The secret is a deploy-time string of unknown shape — it may be hex,
 * base64, or a passphrase someone typed — and AES-256 needs exactly 32 bytes of *uniform* key.
 * HKDF is the standard answer and it costs one call. The info string domain-separates this use,
 * so the same secret can key a second purpose later without the two sharing a key.
 */

/** The sealed-format version. Bumping it invalidates every existing cookie by design. */
const VERSION = 'v1';

/** Bytes. GCM's standard nonce length; anything else costs an extra GHASH pass. */
const IV_LENGTH = 12;

const encoder = new TextEncoder();
const decoder = new TextDecoder();

/**
 * Derived once per process. A promise rather than a value because derivation is async, and
 * caching the promise means concurrent first-callers share one derivation instead of racing.
 */
let keyPromise: Promise<CryptoKey> | undefined;

function sessionKey(): Promise<CryptoKey> {
  keyPromise ??= (async () => {
    const material = await crypto.subtle.importKey(
      'raw',
      encoder.encode(env.sessionSecret),
      'HKDF',
      false,
      ['deriveKey'],
    );
    return crypto.subtle.deriveKey(
      {
        name: 'HKDF',
        hash: 'SHA-256',
        // No salt: the secret is already high-entropy deploy configuration, and a random salt
        // would have to be stored beside the cookie — which is where the info string does the
        // domain separation instead.
        salt: new Uint8Array(0),
        info: encoder.encode('paymentflow.portal.session.v1'),
      },
      material,
      { name: 'AES-GCM', length: 256 },
      false,
      ['encrypt', 'decrypt'],
    );
  })();
  return keyPromise;
}

/** Test seam: forces the next seal/unseal to re-derive. Never called by application code. */
export function resetSessionKeyForTesting(): void {
  keyPromise = undefined;
}

function toBase64Url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * Returns `Uint8Array<ArrayBuffer>` rather than plain `Uint8Array` because TypeScript 5.7 made
 * the backing-buffer type a parameter, and Web Crypto's `BufferSource` excludes
 * `SharedArrayBuffer`. Allocating the buffer explicitly is what pins it.
 */
function fromBase64Url(value: string): Uint8Array<ArrayBuffer> {
  const padded = value.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
  const bytes = new Uint8Array(new ArrayBuffer(binary.length));
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/**
 * @returns `v1.<iv>.<ciphertext‖tag>`, base64url throughout.
 *
 * A fresh random IV per seal. GCM's failure mode under IV reuse is catastrophic rather than
 * gradual — it leaks the authentication key, not just a plaintext — so the IV is never derived
 * from anything about the session.
 */
export async function seal(payload: unknown): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(IV_LENGTH));
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: encoder.encode(VERSION) },
    await sessionKey(),
    encoder.encode(JSON.stringify(payload)),
  );
  return `${VERSION}.${toBase64Url(iv)}.${toBase64Url(new Uint8Array(ciphertext))}`;
}

/**
 * @returns the payload, or `null` for anything that is not an intact cookie this server sealed.
 *
 * Every failure returns `null` rather than throwing, and they are deliberately indistinguishable:
 * a wrong version, a truncated cookie, a flipped bit, a value sealed under a rotated secret, and
 * a cookie from a different deployment all look identical to the caller. The caller's only
 * correct response to any of them is "there is no session", and giving it a reason to branch
 * would turn this into an oracle for probing the format.
 */
export async function unseal<T>(sealed: string | undefined): Promise<T | null> {
  if (!sealed) return null;

  const parts = sealed.split('.');
  if (parts.length !== 3) return null;
  const [version, ivPart, payloadPart] = parts as [string, string, string];
  if (version !== VERSION) return null;

  try {
    const iv = fromBase64Url(ivPart);
    if (iv.length !== IV_LENGTH) return null;

    const plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv, additionalData: encoder.encode(version) },
      await sessionKey(),
      fromBase64Url(payloadPart),
    );
    return JSON.parse(decoder.decode(plaintext)) as T;
  } catch {
    // Tampering, truncation, a rotated secret, or malformed base64. All the same answer.
    return null;
  }
}
