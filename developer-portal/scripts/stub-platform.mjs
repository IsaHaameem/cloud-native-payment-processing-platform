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

/**
 * The accounts this stub knows about, keyed by email.
 *
 * `ada@example.com` is the established one: onboarded, with a merchant. Registration adds more,
 * which is what lets the public-entry suite walk a genuinely new user from `/signup` through
 * `/onboarding` — a journey that cannot be tested against an account that is already finished.
 */
const users = new Map();

/** The one account the tests sign in as. */
const USER = {
  id: '11111111-2222-3333-4444-555555555555',
  email: 'ada@example.com',
  password: 'correct horse battery staple',
  roles: ['USER'],
  merchantId: '66666666-7777-8888-9999-000000000000',
  businessName: 'Ada Lovelace Ltd',
  contactEmail: 'ada@example.com',
};

function seedUsers() {
  users.clear();
  users.set(USER.email, { ...USER });
}
seedUsers();

let nextUserId = 0;
let nextMerchantId = 0;

/** Seconds. Overridable so a test can make an access token that is already stale. */
const accessTtlSeconds = () => Number(process.env.STUB_ACCESS_TTL ?? 900);

/**
 * Live refresh tokens, mapped to the email that owns them.
 *
 * Deleting on use is what makes rotation single-use, as in the real service. It became a map
 * rather than a set when the stub grew a second account: a rotation has to reissue a token for
 * the *right* user, and a set cannot say which one presented it.
 */
const liveRefreshTokens = new Map();

const calls = [];

/**
 * How many rotations were in flight at once, at the busiest moment (M23.2a).
 *
 * ── Why the stub measures this rather than the suite counting calls ──────────────────
 *
 * D197's guarantee is that *concurrent* refreshes collapse to one call — an in-process mutex
 * plus a replay cache. A total call count was a good proxy for that only while the portal's
 * navigation graph had no reachable links: the moment `/dashboard` became a real destination,
 * Next.js began prefetching it from every authenticated page, and each prefetch is an ordinary
 * request that passes through the middleware and may legitimately rotate. Those rotations are
 * *sequential*, each one rotating the then-current token, and none of them is the failure D197
 * exists to prevent.
 *
 * This counts the failure directly instead. Two rotations overlapping in time is exactly what
 * the mutex must never allow, and it is what removing the mutex would immediately produce.
 */
let refreshesInFlight = 0;
let maxRefreshesInFlight = 0;

/**
 * How many rotations were refused because the token had already been used (M23.2a).
 *
 * ── This is the number that actually discriminates ───────────────────────────────────
 *
 * Measured, not assumed: with the coordinator's mutex removed, ten navigations produced eighteen
 * rotations, **nine 401s here, and nine users signed out**. With it, this stays at zero however
 * many rotations the traffic happens to cause.
 *
 * That makes it the right assertion for a browser suite, because it is insensitive to the two
 * things that legitimately vary — how many requests the navigation graph generates (prefetching
 * changed that in M23.2a) and whether they arrive together or in sequence — and sensitive to the
 * one thing that must never happen: a request presenting a token some other request already
 * rotated away. That is the 401 that becomes a logout mid-page-load, which is the entire reason
 * D197 exists.
 *
 * The high-water mark above is kept alongside it as the direct reading of the mutex, but it is
 * not the load-bearing check here: a browser updates its cookie jar between navigations, so
 * requests that the test opens "concurrently" often reach the server one at a time, and an
 * uncoordinated portal can therefore still show one-at-a-time rotations while signing users out.
 * `refresh.test.ts` is where genuinely simultaneous callers are exercised.
 */
let refreshRejections = 0;

function base64url(value) {
  return Buffer.from(value).toString('base64url').replace(/=+$/, '');
}

/**
 * An HS256 JWT. The portal never verifies a signature — the gateway does — so the algorithm is
 * irrelevant to what is under test; what matters is that the claims are shaped exactly like
 * `JwtService` produces them, because the portal reads `sub`, `email`, `roles` and `exp`.
 */
function issueAccessToken(user, ttlSeconds) {
  const now = Math.floor(Date.now() / 1000);
  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64url(
    JSON.stringify({
      iss: 'https://identity.paymentflow.local',
      sub: user.id,
      email: user.email,
      roles: user.roles,
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

function issueRefreshToken(user) {
  const token = `stub-refresh-${randomUUID()}`;
  liveRefreshTokens.set(token, user.email);
  return token;
}

function authResponse(user) {
  const ttl = accessTtlSeconds();
  return {
    accessToken: issueAccessToken(user, ttl),
    refreshToken: issueRefreshToken(user),
    tokenType: 'Bearer',
    expiresIn: ttl,
  };
}

/**
 * The account a bearer token belongs to.
 *
 * The stub does not verify the signature — the gateway does that, and nothing in the portal
 * ever does — so this reads the `email` claim, which is exactly the amount of trust a stub
 * standing in for an authenticated tier needs.
 */
function userFromAuthorization(header) {
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) return undefined;
  const payload = header.slice('Bearer '.length).split('.')[1];
  if (!payload) return undefined;
  try {
    const claims = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    return users.get(claims.email);
  } catch {
    return undefined;
  }
}

function merchantResponse(user) {
  return {
    id: user.merchantId,
    businessName: user.businessName,
    contactEmail: user.contactEmail,
    webhookUrl: null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
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
      maxConcurrentRefreshes: maxRefreshesInFlight,
      refreshRejections,
    });
  }

  if (path === '/__stub/reset') {
    calls.length = 0;
    liveRefreshTokens.clear();
    maxRefreshesInFlight = 0;
    refreshRejections = 0;
    // Accounts created by a previous test are cleared too, so a suite that registers
    // `new@example.com` twice does not fail the second time with a 409 it did not ask for.
    seedUsers();
    return send(response, 200, { ok: true });
  }

  /** Kills every live refresh token — the "signed out elsewhere" / "revoked" scenario. */
  if (path === '/__stub/revoke-all') {
    liveRefreshTokens.clear();
    return send(response, 200, { ok: true });
  }

  /*
   * `AuthController.register`: 201 with a `UserResponse` and **no tokens**, 409 on a duplicate.
   * Registration does not enable-gate login in the real service either — `User.create` sets
   * `enabled` and `AuthService.login` never consults `emailVerified` — so a freshly registered
   * account can sign in immediately here, exactly as it can there.
   */
  if (path === '/api/v1/auth/register' && request.method === 'POST') {
    const body = await readJson(request);
    const email = String(body.email ?? '')
      .trim()
      .toLowerCase();

    if (!email.includes('@') || String(body.password ?? '').length < 8) {
      return send(response, 400, { type: 'invalid_request_error', code: 'validation_error' });
    }
    if (users.has(email)) {
      return send(response, 409, { type: 'invalid_request_error', code: 'conflict' });
    }

    const user = {
      id: `00000000-0000-4000-8000-${String(++nextUserId).padStart(12, '0')}`,
      email,
      password: body.password,
      roles: ['USER'],
      // No merchant: the whole point of a newly registered account, and the state the
      // onboarding half of the entry flow exists to resolve.
      merchantId: undefined,
      businessName: undefined,
      contactEmail: undefined,
      fullName: body.fullName,
    };
    users.set(email, user);
    return send(response, 201, {
      id: user.id,
      email: user.email,
      fullName: user.fullName ?? null,
      roles: user.roles,
      enabled: true,
      emailVerified: false,
      createdAt: new Date().toISOString(),
    });
  }

  if (path === '/api/v1/auth/login' && request.method === 'POST') {
    const body = await readJson(request);
    const user = users.get(String(body.email ?? '').toLowerCase());
    if (!user || body.password !== user.password) {
      return send(response, 401, { type: 'authentication_error', code: 'unauthorized' });
    }
    return send(response, 200, authResponse(user));
  }

  if (path === '/api/v1/auth/refresh' && request.method === 'POST') {
    const body = await readJson(request);
    // Single-use, exactly as `RefreshTokenService.rotate` behaves: present a token that has
    // already been rotated and you get 401, not a second rotation.
    const owner =
      typeof body.refreshToken === 'string' ? liveRefreshTokens.get(body.refreshToken) : undefined;
    if (owner === undefined || !liveRefreshTokens.delete(body.refreshToken)) {
      refreshRejections += 1;
      return send(response, 401, { type: 'authentication_error', code: 'unauthorized' });
    }

    // Counted around the latency below, so the high-water mark reflects rotations that were
    // genuinely simultaneous — which is the property the coordinator guarantees.
    refreshesInFlight += 1;
    maxRefreshesInFlight = Math.max(maxRefreshesInFlight, refreshesInFlight);
    try {
      // A little latency, so concurrent refreshes genuinely overlap rather than serialising by
      // accident — without it the coordination test could pass against no coordination at all.
      await new Promise((resolve) => setTimeout(resolve, 40));
      return send(response, 200, authResponse(users.get(owner)));
    } finally {
      refreshesInFlight -= 1;
    }
  }

  if (path === '/api/v1/auth/logout' && request.method === 'POST') {
    const body = await readJson(request);
    if (typeof body.refreshToken === 'string') liveRefreshTokens.delete(body.refreshToken);
    return send(response, 204, undefined);
  }

  if (path === '/api/v1/merchants/me' && request.method === 'GET') {
    const user = userFromAuthorization(request.headers.authorization);
    if (!user) {
      return send(response, 401, { type: 'authentication_error', code: 'unauthorized' });
    }
    // 404 for a user who has not onboarded, mirroring `MerchantService.getMine`, which throws
    // `ResourceNotFoundException`. That status is a *state* the portal routes on, so a stub that
    // answered 200 with an empty body would hide the branch the entry flow depends on.
    if (user.merchantId === undefined) {
      return send(response, 404, { type: 'invalid_request_error', code: 'not_found' });
    }
    return send(response, 200, merchantResponse(user));
  }

  /*
   * `MerchantController.onboard`: 201 with `{ merchant, apiKeys }`, and 409 for an owner who
   * already has one — the single-merchant-per-owner rule `MerchantService.onboard` enforces, and
   * the branch the action's recovery path is written against.
   *
   * The four starter keys are returned because the real endpoint returns them; the portal is
   * expected to discard them, and `onboarding.test.ts` asserts that it does.
   */
  if (path === '/api/v1/merchants' && request.method === 'POST') {
    const user = userFromAuthorization(request.headers.authorization);
    if (!user) {
      return send(response, 401, { type: 'authentication_error', code: 'unauthorized' });
    }
    if (user.merchantId !== undefined) {
      return send(response, 409, { type: 'invalid_request_error', code: 'conflict' });
    }

    const body = await readJson(request);
    if (!body.businessName || !String(body.contactEmail ?? '').includes('@')) {
      return send(response, 400, { type: 'invalid_request_error', code: 'validation_error' });
    }

    user.merchantId = `99999999-0000-4000-8000-${String(++nextMerchantId).padStart(12, '0')}`;
    user.businessName = body.businessName;
    user.contactEmail = body.contactEmail;

    return send(response, 201, {
      merchant: merchantResponse(user),
      apiKeys: ['pk_test', 'sk_test', 'pk_live', 'sk_live'].map((prefix) => ({
        id: randomUUID(),
        prefix,
        secret: `${prefix}_stub_secret_never_shown`,
      })),
    });
  }

  return send(response, 404, { type: 'invalid_request_error', code: 'not_found' });
});

server.listen(PORT, () => {
  console.log(`stub platform listening on http://localhost:${PORT}`);
});
