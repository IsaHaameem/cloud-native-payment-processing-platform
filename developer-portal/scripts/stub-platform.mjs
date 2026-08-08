/**
 * A stand-in for the gateway, for the portal's browser tests (M23.2).
 *
 * ── Why a stub rather than the real stack ─────────────────────────────────────────────
 *
 * What M23.2 owns is the portal's half of authentication: sealing, refreshing, redirecting,
 * CSRF, and the promise that no token reaches the browser. Testing those against nine containers
 * would make the suite slow, flaky and — the real problem — unable to produce the situations that
 * matter. An expired access token, a revoked refresh token and a rotation race are one line each
 * here; against a live identity-service the first needs a fifteen-minute wait.
 *
 * This does not replace integration testing against the real platform, which is what
 * `docker-compose.yml` and the Gradle suites are for. It tests the portal in isolation, which is
 * the layer that has just been written.
 *
 * ── It enforces the contract it stands in for ─────────────────────────────────────────
 *
 * Rotation is single-use and revocation is immediate, mirroring `RefreshTokenService`. A stub
 * that accepted a rotated token would let the portal pass while doing the exact thing the real
 * backend punishes — so the property under test is preserved rather than assumed away.
 *
 * Every request is recorded, and `GET /__stub/calls` reports them, so a test can assert *how
 * many* rotations happened. That count is the whole point of the concurrency test.
 */

import { createServer } from 'node:http';
import { createHmac, randomUUID } from 'node:crypto';

const PORT = Number(process.env.STUB_PORT ?? 4600);

/** The one account the tests sign in as. */
const USER = {
  id: '11111111-2222-3333-4444-555555555555',
  email: 'ada@example.com',
  password: 'correct horse battery staple',
  roles: ['USER'],
  merchantId: '66666666-7777-8888-9999-000000000000',
};

/** Seconds. Overridable so a test can make an access token that is already stale. */
const accessTtlSeconds = () => Number(process.env.STUB_ACCESS_TTL ?? 900);

/** Live refresh tokens. Deleting on use is what makes rotation single-use, as in the real service. */
const liveRefreshTokens = new Set();

const calls = [];

function base64url(value) {
  return Buffer.from(value).toString('base64url').replace(/=+$/, '');
}

/**
 * An HS256 JWT. The portal never verifies a signature — the gateway does — so the algorithm is
 * irrelevant to what is under test; what matters is that the claims are shaped exactly like
 * `JwtService` produces them, because the portal reads `sub`, `email`, `roles` and `exp`.
 */
function issueAccessToken(ttlSeconds) {
  const now = Math.floor(Date.now() / 1000);
  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64url(
    JSON.stringify({
      iss: 'https://identity.paymentflow.local',
      sub: USER.id,
      email: USER.email,
      roles: USER.roles,
      iat: now,
      exp: now + ttlSeconds,
      jti: randomUUID(),
    }),
  );
  const signature = createHmac('sha256', 'stub-only')
    .update(`${header}.${payload}`)
    .digest('base64url');
  return `${header}.${payload}.${signature}`;
}

function issueRefreshToken() {
  const token = `stub-refresh-${randomUUID()}`;
  liveRefreshTokens.add(token);
  return token;
}

function authResponse() {
  const ttl = accessTtlSeconds();
  return {
    accessToken: issueAccessToken(ttl),
    refreshToken: issueRefreshToken(),
    tokenType: 'Bearer',
    expiresIn: ttl,
  };
}

function send(response, status, body) {
  const payload = body === undefined ? '' : JSON.stringify(body);
  response.writeHead(status, {
    'content-type': 'application/json',
    'content-length': Buffer.byteLength(payload),
  });
  response.end(payload);
}

async function readJson(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  if (chunks.length === 0) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    return {};
  }
}

const server = createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', `http://localhost:${PORT}`);
  const path = url.pathname;
  calls.push({ method: request.method, path, at: Date.now() });

  if (path === '/__stub/calls') {
    return send(response, 200, {
      calls,
      refreshCount: calls.filter((c) => c.path === '/api/v1/auth/refresh').length,
      loginCount: calls.filter((c) => c.path === '/api/v1/auth/login').length,
      logoutCount: calls.filter((c) => c.path === '/api/v1/auth/logout').length,
      liveRefreshTokens: liveRefreshTokens.size,
    });
  }

  if (path === '/__stub/reset') {
    calls.length = 0;
    liveRefreshTokens.clear();
    return send(response, 200, { ok: true });
  }

  /** Kills every live refresh token — the "signed out elsewhere" / "revoked" scenario. */
  if (path === '/__stub/revoke-all') {
    liveRefreshTokens.clear();
    return send(response, 200, { ok: true });
  }

  if (path === '/api/v1/auth/login' && request.method === 'POST') {
    const body = await readJson(request);
    if (body.email !== USER.email || body.password !== USER.password) {
      return send(response, 401, { type: 'authentication_error', code: 'unauthorized' });
    }
    return send(response, 200, authResponse());
  }

  if (path === '/api/v1/auth/refresh' && request.method === 'POST') {
    const body = await readJson(request);
    // Single-use, exactly as `RefreshTokenService.rotate` behaves: present a token that has
    // already been rotated and you get 401, not a second rotation.
    if (typeof body.refreshToken !== 'string' || !liveRefreshTokens.delete(body.refreshToken)) {
      return send(response, 401, { type: 'authentication_error', code: 'unauthorized' });
    }
    // A little latency, so concurrent refreshes genuinely overlap rather than serialising by
    // accident — without it the coordination test could pass against no coordination at all.
    await new Promise((resolve) => setTimeout(resolve, 40));
    return send(response, 200, authResponse());
  }

  if (path === '/api/v1/auth/logout' && request.method === 'POST') {
    const body = await readJson(request);
    if (typeof body.refreshToken === 'string') liveRefreshTokens.delete(body.refreshToken);
    return send(response, 204, undefined);
  }

  if (path === '/api/v1/merchants/me' && request.method === 'GET') {
    const authorization = request.headers.authorization ?? '';
    if (!authorization.startsWith('Bearer ')) {
      return send(response, 401, { type: 'authentication_error', code: 'unauthorized' });
    }
    return send(response, 200, { id: USER.merchantId, name: 'Ada Lovelace Ltd' });
  }

  return send(response, 404, { type: 'invalid_request_error', code: 'not_found' });
});

server.listen(PORT, () => {
  console.log(`stub platform listening on http://localhost:${PORT}`);
});
