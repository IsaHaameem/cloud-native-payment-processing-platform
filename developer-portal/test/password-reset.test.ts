import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  IdentityUnavailableError,
  InvalidResetTokenError,
  RegistrationRejectedError,
  confirmPasswordReset,
  requestPasswordReset,
} from '@/lib/session/identity';
import {
  RESET_THROTTLE_LIMITS,
  consumeResetAllowance,
  resetPasswordResetThrottleForTesting,
} from '@/lib/security/reset-throttle';

/**
 * Password recovery (M23.2b), against the endpoints identity-service has served since M15.
 *
 * The property most of this file exists to protect is **indistinguishability**: every path
 * through the request flow must produce the same answer, because identity-service goes out of its
 * way not to reveal which addresses have accounts and a portal that leaks it in a different shape
 * has undone that work. The throttle is the interesting case — a limiter that answered
 * differently when it fired would be exactly such a leak.
 */

const fetchMock = vi.fn();

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

const guard = vi.hoisted(() => vi.fn());
vi.mock('@/lib/security/form-guard', async () => {
  const actual = await vi.importActual<typeof import('@/lib/security/form-guard')>(
    '@/lib/security/form-guard',
  );
  return { ...actual, guardFormRequest: guard };
});

const { forgotPasswordAction } = await import('@/app/(auth)/forgot-password/actions');
const { resetPasswordAction } = await import('@/app/(auth)/reset-password/actions');

/**
 * `null` rather than `''` for the bodiless statuses: the `Response` constructor rejects a body on
 * 204, which is exactly the status these two endpoints answer with on success.
 */
function jsonResponse(status: number, body?: unknown): Response {
  const bodiless = status === 204 || status === 205 || status === 304;
  return new Response(bodiless || body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

/**
 * A *fresh* `Response` per call.
 *
 * `mockResolvedValue` hands back one instance every time, and a body can only be read once — so
 * the second call in any loop fails with "Body has already been read", which looks like a defect
 * in the code under test and is not.
 */
function alwaysAnswer(status: number, body?: unknown): void {
  fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(status, body)));
}

function form(fields: Record<string, string>): FormData {
  const data = new FormData();
  data.set('csrfToken', 'a'.repeat(64));
  for (const [key, value] of Object.entries(fields)) data.set(key, value);
  return data;
}

async function submitReset(fields: Record<string, string>) {
  try {
    const state = await resetPasswordAction({ error: undefined, field: undefined }, form(fields));
    return { state, location: undefined };
  } catch (error) {
    if (error instanceof RedirectSignal) return { state: undefined, location: error.location };
    throw error;
  }
}

const askFor = (email: string) =>
  forgotPasswordAction({ sent: false, error: undefined }, form({ email }));

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
  guard.mockReset().mockResolvedValue(undefined);
  resetPasswordResetThrottleForTesting();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('the recovery client', () => {
  it('posts the contract shape for a reset request', async () => {
    fetchMock.mockResolvedValue(jsonResponse(202));
    await requestPasswordReset('ada@example.com');

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/auth/password-reset/request');
    expect(JSON.parse(init.body as string)).toEqual({ email: 'ada@example.com' });
  });

  it('returns normally for every answer about the address', async () => {
    // The backend answers 202 regardless; this asserts the portal adds no branch of its own that
    // could reintroduce the distinction the backend refuses to make.
    for (const status of [202, 200, 404, 400]) {
      alwaysAnswer(status);
      await expect(requestPasswordReset('nobody@example.com')).resolves.toBeUndefined();
    }
  });

  it('raises only when the platform itself is unreachable', async () => {
    fetchMock.mockResolvedValue(jsonResponse(503));
    await expect(requestPasswordReset('ada@example.com')).rejects.toBeInstanceOf(
      IdentityUnavailableError,
    );
  });

  it('posts the contract shape for a confirmation', async () => {
    alwaysAnswer(204);
    await confirmPasswordReset('the-token', 'a new long password');

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/auth/password-reset/confirm');
    expect(JSON.parse(init.body as string)).toEqual({
      token: 'the-token',
      newPassword: 'a new long password',
    });
  });

  it('maps 401 to one invalid-token error, whatever the reason', async () => {
    // Unknown, expired and already-consumed are one error at the backend too — telling them apart
    // would tell a holder of a guessed token which guesses are close.
    fetchMock.mockResolvedValue(jsonResponse(401, { code: 'unauthorized' }));
    await expect(confirmPasswordReset('spent', 'a new long password')).rejects.toBeInstanceOf(
      InvalidResetTokenError,
    );
  });

  it('maps 400 to a rejected password', async () => {
    fetchMock.mockResolvedValue(jsonResponse(400, { code: 'validation_error' }));
    await expect(confirmPasswordReset('token', 'short')).rejects.toBeInstanceOf(
      RegistrationRejectedError,
    );
  });

  it('never puts the reset token or the new password in an error message', async () => {
    fetchMock.mockResolvedValue(jsonResponse(503));
    const error = await confirmPasswordReset('secret-reset-token', 'secret-new-password').then(
      () => new Error('expected a rejection'),
      (e: unknown) => e as Error,
    );
    expect(error.message).not.toContain('secret-reset-token');
    expect(error.message).not.toContain('secret-new-password');
  });
});

describe('requesting a link', () => {
  it('confirms for an address that exists', async () => {
    fetchMock.mockResolvedValue(jsonResponse(202));
    await expect(askFor('ada@example.com')).resolves.toEqual({ sent: true, error: undefined });
  });

  it('answers identically for an address that does not', async () => {
    fetchMock.mockResolvedValue(jsonResponse(202));
    expect(await askFor('nobody@example.com')).toEqual({ sent: true, error: undefined });
  });

  it('answers identically for a malformed address, without calling the platform', async () => {
    // Reported no differently, so the shape of the response cannot be used to probe. The address
    // simply never reaches identity-service.
    expect(await askFor('not-an-email')).toEqual({ sent: true, error: undefined });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('normalises the address the same way identity-service does', async () => {
    fetchMock.mockResolvedValue(jsonResponse(202));
    await askFor('  Ada@Example.COM ');

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(init.body as string).email).toBe('ada@example.com');
  });

  it('stops calling the platform once an address has spent its allowance', async () => {
    alwaysAnswer(202);
    for (let i = 0; i < RESET_THROTTLE_LIMITS.maxRequests; i++) {
      await askFor('ada@example.com');
    }
    const callsBefore = fetchMock.mock.calls.length;

    await askFor('ada@example.com');
    expect(fetchMock.mock.calls.length).toBe(callsBefore);
  });

  it('but the throttled answer is indistinguishable from the ordinary one', async () => {
    // The property that keeps the limiter from becoming the oracle the endpoint refuses to be.
    alwaysAnswer(202);
    for (let i = 0; i < RESET_THROTTLE_LIMITS.maxRequests; i++) {
      await askFor('ada@example.com');
    }
    expect(await askFor('ada@example.com')).toEqual({ sent: true, error: undefined });
  });

  it('counts each address separately', async () => {
    fetchMock.mockResolvedValue(jsonResponse(202));
    for (let i = 0; i < RESET_THROTTLE_LIMITS.maxRequests; i++) {
      consumeResetAllowance('ada@example.com');
    }
    expect(consumeResetAllowance('ada@example.com')).toBe(false);
    expect(consumeResetAllowance('grace@example.com')).toBe(true);
  });

  it('lets the allowance lapse once the window has passed', async () => {
    const now = Date.now();
    for (let i = 0; i < RESET_THROTTLE_LIMITS.maxRequests; i++) {
      consumeResetAllowance('ada@example.com', now);
    }
    expect(consumeResetAllowance('ada@example.com', now)).toBe(false);
    expect(consumeResetAllowance('ada@example.com', now + RESET_THROTTLE_LIMITS.windowMs + 1)).toBe(
      true,
    );
  });

  it('reports an outage as an outage — the one thing that is not about the address', async () => {
    fetchMock.mockResolvedValue(jsonResponse(503));
    const state = await askFor('ada@example.com');
    expect(state.sent).toBe(false);
    expect(state.error).toMatch(/temporarily unavailable/i);
  });

  it('refuses a request that fails the form guard, before touching the platform', async () => {
    guard.mockResolvedValue('csrf');
    const state = await askFor('ada@example.com');
    expect(state.sent).toBe(false);
    expect(state.error).toMatch(/expired/i);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('setting the new password', () => {
  const VALID = {
    token: 'a-valid-reset-token',
    password: 'a new long password',
    confirm: 'a new long password',
  };

  it('sets the password and sends the user to sign in', async () => {
    alwaysAnswer(204);
    const { location } = await submitReset(VALID);
    expect(location).toBe('/login?reset=1');
  });

  it('does not carry the address into the redirect', async () => {
    // Nothing here established which address it was, and putting an account identifier in a URL
    // for a cosmetic pre-fill is a trade with no upside.
    alwaysAnswer(204);
    const { location } = await submitReset(VALID);
    expect(location).not.toContain('@');
  });

  it('reports a spent or expired token as one thing, with a way forward', async () => {
    fetchMock.mockResolvedValue(jsonResponse(401));
    const { state } = await submitReset(VALID);
    expect(state?.error).toMatch(/no longer valid/i);
    expect(state?.error).toMatch(/request a new one/i);
  });

  it('refuses a submission with no token, without calling the platform', async () => {
    const { state } = await submitReset({ ...VALID, token: '' });
    expect(state?.error).toMatch(/no longer valid/i);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('enforces the platform password bounds before spending the token', async () => {
    // Spending a single-use token to be told the password was too short would cost the user
    // another email.
    expect((await submitReset({ ...VALID, password: 'abc', confirm: 'abc' })).state?.field).toBe(
      'password',
    );
    const long = 'x'.repeat(73);
    expect((await submitReset({ ...VALID, password: long, confirm: long })).state?.field).toBe(
      'password',
    );
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('checks the confirmation server-side, not only in the browser', async () => {
    const { state } = await submitReset({ ...VALID, confirm: 'something else entirely' });
    expect(state?.field).toBe('confirm');
    expect(state?.error).toMatch(/do not match/i);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('refuses a submission that fails the form guard, before touching the platform', async () => {
    guard.mockResolvedValue('origin');
    const { state } = await submitReset(VALID);
    expect(state?.error).toMatch(/did not come from the portal/i);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
