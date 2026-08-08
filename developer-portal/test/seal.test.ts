import { describe, expect, it } from 'vitest';

import { seal, unseal } from '@/lib/session/seal';
import {
  SESSION_ABSOLUTE_MAX_AGE_SECONDS,
  SESSION_VERSION,
  type Session,
  decodeSession,
  encodeSession,
} from '@/lib/session/session';

/**
 * Sealing, unsealing, and the staleness rules (M23.2).
 *
 * These assert the properties the cookie's security actually rests on — confidentiality,
 * integrity, and the refusal to accept anything this server did not produce — rather than that
 * a round trip works.
 */

function aSession(overrides: Partial<Session> = {}): Session {
  const now = Date.now();
  return {
    version: SESSION_VERSION,
    userId: '11111111-2222-3333-4444-555555555555',
    email: 'ada@example.com',
    roles: ['USER'],
    accessToken: 'header.payload.signature',
    accessExpiresAt: now + 15 * 60 * 1000,
    refreshToken: 'opaque-refresh-token-value',
    refreshExpiresAt: now + 7 * 24 * 60 * 60 * 1000,
    merchantId: '66666666-7777-8888-9999-000000000000',
    mode: 'test',
    createdAt: now,
    ...overrides,
  };
}

describe('seal / unseal', () => {
  it('round-trips a payload', async () => {
    const sealed = await seal({ hello: 'world', n: 42 });
    await expect(unseal(sealed)).resolves.toEqual({ hello: 'world', n: 42 });
  });

  it('does not leave the plaintext readable in the cookie', async () => {
    const sealed = await seal(aSession());

    // The whole reason the cookie is encrypted rather than merely signed. A signed-only cookie
    // would pass a round-trip test identically and fail this one.
    expect(sealed).not.toContain('opaque-refresh-token-value');
    expect(sealed).not.toContain('ada@example.com');
    expect(sealed).not.toContain('header.payload.signature');
  });

  it('produces a different ciphertext each time, so two sessions are not correlatable', async () => {
    const payload = aSession();
    const first = await seal(payload);
    const second = await seal(payload);

    // A fresh IV per seal. Equal ciphertexts would mean a deterministic nonce, which for GCM
    // leaks the authentication key rather than merely a plaintext.
    expect(first).not.toEqual(second);
    await expect(unseal(first)).resolves.toEqual(await unseal(second));
  });

  it('rejects a flipped bit in the ciphertext', async () => {
    const sealed = await seal(aSession());
    const parts = sealed.split('.');
    const body = parts[2] as string;
    // Change one character of the payload segment.
    const tampered = `${parts[0]}.${parts[1]}.${body[0] === 'A' ? 'B' : 'A'}${body.slice(1)}`;

    await expect(unseal(tampered)).resolves.toBeNull();
  });

  it('rejects a flipped bit in the IV', async () => {
    const sealed = await seal(aSession());
    const parts = sealed.split('.');
    const iv = parts[1] as string;
    const tampered = `${parts[0]}.${iv[0] === 'A' ? 'B' : 'A'}${iv.slice(1)}.${parts[2]}`;

    await expect(unseal(tampered)).resolves.toBeNull();
  });

  it('rejects a rewritten version prefix, because the version is authenticated', async () => {
    const sealed = await seal(aSession());
    const parts = sealed.split('.');

    // The version is GCM additional-authenticated-data, not merely a label. Rewriting it must
    // fail the tag rather than select a different set of rules — this is the downgrade check.
    await expect(unseal(`v0.${parts[1]}.${parts[2]}`)).resolves.toBeNull();
    await expect(unseal(`v2.${parts[1]}.${parts[2]}`)).resolves.toBeNull();
  });

  it('rejects structurally wrong input without throwing', async () => {
    for (const bad of ['', 'not-a-cookie', 'v1.only-two', 'v1.a.b.c', 'v1..', 'v1.!!!.!!!']) {
      await expect(unseal(bad)).resolves.toBeNull();
    }
    await expect(unseal(undefined)).resolves.toBeNull();
  });

  it('rejects a truncated ciphertext', async () => {
    const sealed = await seal(aSession());
    await expect(unseal(sealed.slice(0, sealed.length - 8))).resolves.toBeNull();
  });
});

describe('decodeSession', () => {
  it('accepts a session this server sealed', async () => {
    const session = aSession();
    await expect(decodeSession(await encodeSession(session))).resolves.toEqual(session);
  });

  it('refuses a superseded session version', async () => {
    const sealed = await encodeSession(aSession({ version: SESSION_VERSION + 1 }));
    await expect(decodeSession(sealed)).resolves.toBeNull();
  });

  it('refuses a session past its absolute age, however fresh its tokens are', async () => {
    const now = Date.now();
    const sealed = await encodeSession(
      aSession({
        createdAt: now - (SESSION_ABSOLUTE_MAX_AGE_SECONDS + 1) * 1000,
        // Deliberately valid: rotation can keep tokens fresh forever, and the absolute ceiling
        // is the only thing that eventually ends such a session.
        accessExpiresAt: now + 15 * 60 * 1000,
        refreshExpiresAt: now + 7 * 24 * 60 * 60 * 1000,
      }),
    );
    await expect(decodeSession(sealed, now)).resolves.toBeNull();
  });

  it('refuses a session whose refresh token has expired', async () => {
    const now = Date.now();
    const sealed = await encodeSession(aSession({ refreshExpiresAt: now - 1 }));
    // There is no route back to a valid access token, so the session is over even though the
    // cookie decrypts perfectly.
    await expect(decodeSession(sealed, now)).resolves.toBeNull();
  });

  it('refuses a decryptable payload of the wrong shape', async () => {
    // Sealed by this server — so integrity passes — but not a session. This is the guard
    // against our own past selves, not against an attacker.
    const sealed = await seal({ version: SESSION_VERSION, userId: 12345 });
    await expect(decodeSession(sealed)).resolves.toBeNull();
  });

  it('refuses a session carrying a mode that is not a mode', async () => {
    const sealed = await seal({ ...aSession(), mode: 'production' });
    await expect(decodeSession(sealed)).resolves.toBeNull();
  });

  it('stays inside the 4KB per-cookie budget', async () => {
    // A realistic RS256 access token is ~800 bytes; this is deliberately larger.
    const sealed = await encodeSession(aSession({ accessToken: 'x'.repeat(1600) }));
    expect(sealed.length).toBeLessThan(4096);
  });
});
