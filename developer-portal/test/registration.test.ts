import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  EmailAlreadyRegisteredError,
  IdentityUnavailableError,
  RegistrationRejectedError,
  register,
} from '@/lib/session/identity';

/**
 * Account creation (M23.2a).
 *
 * Two layers, tested separately because they fail differently: the identity client, which turns
 * HTTP statuses into the four outcomes the portal can act on, and the Server Action, which turns
 * those outcomes plus the user's input into a message or a redirect.
 *
 * `fetch` is replaced rather than the module that calls it, so the real request is built and the
 * real status handling runs — everything except the network.
 */

const fetchMock = vi.fn();

/*
 * The action's ambient dependencies.
 *
 * `next/navigation`'s `redirect` throws a sentinel in production; here it throws one this file
 * can catch and read the destination out of, which is what makes "where does a successful signup
 * send the user" an assertion rather than a manual check.
 */
class RedirectSignal extends Error {
  constructor(readonly location: string) {
    super(`redirect:${location}`);
  }
}

vi.mock('next/navigation', () => ({
  redirect: (location: string) => {
    throw new RedirectSignal(location);
  },
}));

const assertSameOrigin = vi.hoisted(() => vi.fn());
const assertCsrf = vi.hoisted(() => vi.fn());

vi.mock('@/lib/session/lifecycle', () => ({ assertRequestIsSameOrigin: assertSameOrigin }));
vi.mock('@/lib/security/csrf', async () => {
  const actual = await vi.importActual<typeof import('@/lib/security/csrf')>('@/lib/security/csrf');
  return { ...actual, assertCsrfToken: assertCsrf };
});

const { signupAction } = await import('@/app/(auth)/signup/actions');

function jsonResponse(status: number, body?: unknown): Response {
  return new Response(body === undefined ? '' : JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  data.set('csrfToken', 'a'.repeat(64));
  for (const [key, value] of Object.entries(fields)) data.set(key, value);
  return data;
}

const VALID = {
  fullName: 'Ada Lovelace',
  email: 'ada@example.com',
  password: 'correct horse battery staple',
};

/** Runs the action and reports where it redirected, or `undefined` if it returned a state. */
async function submit(fields: Record<string, string>) {
  try {
    const state = await signupAction({ error: undefined, field: undefined }, form(fields));
    return { state, location: undefined };
  } catch (error) {
    if (error instanceof RedirectSignal) return { state: undefined, location: error.location };
    throw error;
  }
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  assertSameOrigin.mockReset().mockResolvedValue(undefined);
  assertCsrf.mockReset().mockResolvedValue(undefined);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('the registration client', () => {
  it('posts the contract shape to the gateway', async () => {
    fetchMock.mockResolvedValue(jsonResponse(201, { id: 'usr_1', email: VALID.email }));
    await register(VALID.email, VALID.password, VALID.fullName);

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/auth/register');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({
      email: VALID.email,
      password: VALID.password,
      fullName: VALID.fullName,
    });
  });

  it('omits fullName rather than sending an empty one', async () => {
    // `RegisterRequest.fullName` carries `@Size` and no `@NotBlank`, so absent and "" are
    // different things to the platform. Absent is what "not supplied" means.
    fetchMock.mockResolvedValue(jsonResponse(201, {}));
    await register(VALID.email, VALID.password, undefined);

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(init.body as string)).not.toHaveProperty('fullName');
  });

  it('maps 409 to a taken address', async () => {
    fetchMock.mockResolvedValue(jsonResponse(409, { code: 'conflict' }));
    await expect(register(VALID.email, VALID.password, undefined)).rejects.toBeInstanceOf(
      EmailAlreadyRegisteredError,
    );
  });

  it('maps 400 to a rejected payload', async () => {
    fetchMock.mockResolvedValue(jsonResponse(400, { code: 'validation_error' }));
    await expect(register('bad', 'short', undefined)).rejects.toBeInstanceOf(
      RegistrationRejectedError,
    );
  });

  it('maps 5xx and network failures to unavailability', async () => {
    fetchMock.mockResolvedValue(jsonResponse(503, {}));
    await expect(register(VALID.email, VALID.password, undefined)).rejects.toBeInstanceOf(
      IdentityUnavailableError,
    );

    fetchMock.mockRejectedValue(new TypeError('fetch failed'));
    await expect(register(VALID.email, VALID.password, undefined)).rejects.toBeInstanceOf(
      IdentityUnavailableError,
    );
  });

  it('never puts the password in an error message', async () => {
    fetchMock.mockResolvedValue(jsonResponse(503, {}));
    const error = await register(VALID.email, 'super-secret-password', undefined).then(
      () => new Error('expected a rejection'),
      (e: unknown) => e as Error,
    );
    expect(error.message).not.toContain('super-secret-password');
  });
});

describe('an unreachable platform is reported to the operator (M23.2c)', () => {
  /**
   * The regression, and it is a regression in *diagnosability* rather than in behaviour.
   *
   * A portal pointed at a gateway that was not running produced "Account creation is temporarily
   * unavailable" in the browser and **nothing whatsoever** on the server. The two states an
   * operator most needs to tell apart — the platform is briefly unwell, and this portal is
   * misconfigured — were indistinguishable from both sides, which is how a configuration problem
   * came to be filed as a signup bug.
   *
   * Every assertion below fails on the code as it stood: it wrote no log line at all.
   */
  function captureErrors() {
    return vi.spyOn(console, 'error').mockImplementation(() => undefined);
  }

  it('names the gateway it tried and the variable that configures it', async () => {
    const logged = captureErrors();
    fetchMock.mockRejectedValue(new TypeError('fetch failed'));

    await expect(register(VALID.email, VALID.password, undefined)).rejects.toBeInstanceOf(
      IdentityUnavailableError,
    );

    const message = logged.mock.calls.map((call) => String(call[0])).join('\n');
    expect(message).toContain('/api/v1/auth/register');
    expect(message).toContain('PF_GATEWAY_URL');
  });

  it('says so when the platform answers 5xx', async () => {
    const logged = captureErrors();
    fetchMock.mockResolvedValue(jsonResponse(503, {}));

    await expect(register(VALID.email, VALID.password, undefined)).rejects.toBeInstanceOf(
      IdentityUnavailableError,
    );
    expect(logged.mock.calls.map((c) => String(c[0])).join('\n')).toContain('503');
  });

  it('says so when the gateway rate-limits the attempt', async () => {
    // 429 reaches the user as the same generic sentence, by design. Without a log there is
    // nothing anywhere that distinguishes it from an outage.
    const logged = captureErrors();
    fetchMock.mockResolvedValue(jsonResponse(429, { code: 'rate_limited' }));

    await expect(register(VALID.email, VALID.password, undefined)).rejects.toBeInstanceOf(
      IdentityUnavailableError,
    );
    expect(logged.mock.calls.map((c) => String(c[0])).join('\n')).toContain('429');
  });

  it('never writes the password or the address into the log', async () => {
    // The module's standing rule, extended to the new output. A diagnostic that leaks a
    // credential is worse than no diagnostic.
    const logged = captureErrors();
    fetchMock.mockRejectedValue(new TypeError('fetch failed'));

    await register('someone@example.com', 'super-secret-password', 'Ada Lovelace').catch(
      () => undefined,
    );

    const message = logged.mock.calls.map((call) => String(call[0])).join('\n');
    expect(message).not.toContain('super-secret-password');
    expect(message).not.toContain('someone@example.com');
  });

  it('stays silent when registration succeeds', async () => {
    // A log that fires on the happy path is a log nobody reads.
    const logged = captureErrors();
    fetchMock.mockResolvedValue(jsonResponse(201, {}));

    await register(VALID.email, VALID.password, undefined);
    expect(logged).not.toHaveBeenCalled();
  });

  it('stays silent for a duplicate account, which is not an outage', async () => {
    const logged = captureErrors();
    fetchMock.mockResolvedValue(jsonResponse(409, {}));

    await expect(register(VALID.email, VALID.password, undefined)).rejects.toBeInstanceOf(
      EmailAlreadyRegisteredError,
    );
    expect(logged).not.toHaveBeenCalled();
  });
});

describe('the signup action', () => {
  it('creates the account and hands the user to sign in', async () => {
    fetchMock.mockResolvedValue(jsonResponse(201, {}));
    const { location } = await submit(VALID);

    expect(location).toBeDefined();
    const url = new URL(location as string, 'http://localhost');
    expect(url.pathname).toBe('/login');
    // The flag the login page renders fixed copy from, and the address so it is not retyped.
    expect(url.searchParams.get('registered')).toBe('1');
    expect(url.searchParams.get('email')).toBe(VALID.email);
  });

  it('normalises the email exactly as identity-service does', async () => {
    fetchMock.mockResolvedValue(jsonResponse(201, {}));
    await submit({ ...VALID, email: '  Ada@Example.COM ' });

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(init.body as string).email).toBe('ada@example.com');
  });

  it('carries a deep link through registration and on to sign-in', async () => {
    fetchMock.mockResolvedValue(jsonResponse(201, {}));
    const { location } = await submit({ ...VALID, next: '/payments?status=succeeded' });

    const url = new URL(location as string, 'http://localhost');
    expect(url.searchParams.get('next')).toBe('/payments?status=succeeded');
  });

  it('refuses to carry an off-site next through registration', async () => {
    fetchMock.mockResolvedValue(jsonResponse(201, {}));
    const { location } = await submit({ ...VALID, next: 'https://evil.test/phish' });

    // `safeRedirectPath(…, '')` yields '' for anything unsafe, and an empty `next` is omitted —
    // so the login page falls back to its own default rather than to the attacker's URL.
    expect(new URL(location as string, 'http://localhost').searchParams.has('next')).toBe(false);
  });

  it('rejects a malformed address without calling the platform', async () => {
    const { state } = await submit({ ...VALID, email: 'not-an-email' });
    expect(state?.field).toBe('email');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('enforces the platform password bounds before spending a request', async () => {
    const short = await submit({ ...VALID, password: 'abc' });
    expect(short.state?.field).toBe('password');

    const long = await submit({ ...VALID, password: 'x'.repeat(73) });
    expect(long.state?.field).toBe('password');

    // 72 is BCrypt's input limit and 8 the platform's floor; neither should cost a round trip.
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('reports a duplicate account against the email field, with a way forward', async () => {
    fetchMock.mockResolvedValue(jsonResponse(409, {}));
    const { state } = await submit(VALID);

    expect(state?.field).toBe('email');
    expect(state?.error).toMatch(/already exists/i);
    expect(state?.error).toMatch(/sign in/i);
  });

  it('reports a platform outage as an outage, not as bad input', async () => {
    fetchMock.mockResolvedValue(jsonResponse(503, {}));
    const { state } = await submit(VALID);

    expect(state?.field).toBeUndefined();
    expect(state?.error).toMatch(/temporarily unavailable/i);
  });

  it('refuses a request that fails the CSRF check, before touching the platform', async () => {
    const { CsrfError } = await import('@/lib/security/csrf');
    assertCsrf.mockRejectedValue(new CsrfError());

    const { state } = await submit(VALID);
    expect(state?.error).toMatch(/expired/i);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuses a cross-origin submission, and says so rather than blaming the form', async () => {
    const { CrossOriginRequestError } = await import('@/lib/security/origin');
    assertSameOrigin.mockRejectedValue(new CrossOriginRequestError());

    const { state } = await submit(VALID);
    // M23.2b: this said "expired" until a portal reached on the wrong host reported a signup bug
    // that was neither signup nor an expiry. The two failures are told apart now.
    expect(state?.error).toMatch(/did not come from the portal/i);
    expect(state?.error).not.toMatch(/expired/i);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
