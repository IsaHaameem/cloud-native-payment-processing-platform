'use server';

import { revalidatePath } from 'next/cache';

import { callAs } from '@/lib/api/client';
import { AuthenticationError, PlatformError } from '@/lib/api/errors';
import type { OperationId } from '@/lib/api/transport';
import { GUARD_MESSAGES, guardFormRequest } from '@/lib/security/form-guard';
import { readSession } from '@/lib/session/require';

import { WEBHOOK_IDLE, type WebhookActionState } from './action-state';

/**
 * Webhook endpoint and delivery mutations, as guarded Server Actions (frontend build).
 *
 * `frontend_Design.md §23`: create, rotate secret, disable, delete an endpoint; replay a
 * delivery. Every one is a real `/v1/webhook_endpoints…` or `/v1/webhook_deliveries…` route, so
 * these wrappers add only the CSRF token and origin assertion. The secret from a create or a
 * rotate is returned in the action's own response and shown exactly once — the portal keeps
 * nothing, and only `signingSecretPrefix` is ever displayed afterwards.
 */

function fail(message: string, platform?: PlatformError): WebhookActionState {
  return { ...WEBHOOK_IDLE, error: message, requestId: platform?.requestId };
}

async function run<T>(
  operationId: OperationId,
  csrfToken: FormDataEntryValue | null,
  options: { path?: Record<string, string>; body?: unknown },
): Promise<{ ok: true; data: T } | { ok: false; state: WebhookActionState }> {
  const refused = await guardFormRequest(csrfToken);
  if (refused) return { ok: false, state: fail(GUARD_MESSAGES[refused]) };

  const session = await readSession();
  if (!session) {
    return { ok: false, state: fail('Your session is no longer valid. Sign in again.') };
  }

  try {
    const data = await callAs<T>(operationId, { session, ...options });
    return { ok: true, data };
  } catch (error) {
    if (error instanceof AuthenticationError) {
      return { ok: false, state: fail('Your session is no longer valid. Sign in again.') };
    }
    if (error instanceof PlatformError) return { ok: false, state: fail(error.message, error) };
    throw error;
  }
}

function revalidate() {
  revalidatePath('/developers/webhooks', 'page');
  revalidatePath('/developers/webhooks/[id]', 'page');
}

export async function createWebhookEndpointAction(
  _prev: WebhookActionState,
  formData: FormData,
): Promise<WebhookActionState> {
  const url = String(formData.get('url') ?? '').trim();
  const description = String(formData.get('description') ?? '').trim();
  const events = formData
    .getAll('events')
    .map(String)
    .filter((s) => s.length > 0);

  if (!/^https:\/\/.+/i.test(url)) return fail('Enter an https:// URL.');
  if (events.length === 0) return fail('Choose at least one event type.');

  const result = await run<Record<string, unknown>>(
    'createWebhookEndpoint',
    formData.get('csrfToken'),
    { body: { url, enabledEvents: events, ...(description ? { description } : {}) } },
  );
  if (!result.ok) return result.state;

  revalidate();
  const secret =
    (typeof result.data.signingSecret === 'string' && result.data.signingSecret) ||
    (typeof result.data.secret === 'string' && result.data.secret) ||
    undefined;
  return { ...WEBHOOK_IDLE, ok: true, done: 'created', secret };
}

export async function rotateWebhookSecretAction(
  _prev: WebhookActionState,
  formData: FormData,
): Promise<WebhookActionState> {
  const id = String(formData.get('id') ?? '').trim();
  if (!id) return fail('That endpoint could not be identified.');

  const result = await run<Record<string, unknown>>(
    'rotateWebhookEndpointSecret',
    formData.get('csrfToken'),
    { path: { id } },
  );
  if (!result.ok) return result.state;

  revalidate();
  const secret =
    (typeof result.data.signingSecret === 'string' && result.data.signingSecret) ||
    (typeof result.data.secret === 'string' && result.data.secret) ||
    undefined;
  return { ...WEBHOOK_IDLE, ok: true, done: 'rotated', secret };
}

export async function setWebhookEnabledAction(
  _prev: WebhookActionState,
  formData: FormData,
): Promise<WebhookActionState> {
  const id = String(formData.get('id') ?? '').trim();
  const enabled = String(formData.get('enabled') ?? '') === 'true';
  if (!id) return fail('That endpoint could not be identified.');

  const result = await run('updateWebhookEndpoint', formData.get('csrfToken'), {
    path: { id },
    body: { enabled },
  });
  if (!result.ok) return result.state;
  revalidate();
  return { ...WEBHOOK_IDLE, ok: true, done: 'updated' };
}

export async function deleteWebhookEndpointAction(
  _prev: WebhookActionState,
  formData: FormData,
): Promise<WebhookActionState> {
  const id = String(formData.get('id') ?? '').trim();
  if (!id) return fail('That endpoint could not be identified.');

  const result = await run('deleteWebhookEndpoint', formData.get('csrfToken'), { path: { id } });
  if (!result.ok) return result.state;
  revalidate();
  return { ...WEBHOOK_IDLE, ok: true, done: 'deleted' };
}

export async function replayWebhookDeliveryAction(
  _prev: WebhookActionState,
  formData: FormData,
): Promise<WebhookActionState> {
  const id = String(formData.get('id') ?? '').trim();
  if (!id) return fail('That delivery could not be identified.');

  const result = await run('replayWebhookDelivery', formData.get('csrfToken'), { path: { id } });
  if (!result.ok) return result.state;
  revalidate();
  return { ...WEBHOOK_IDLE, ok: true, done: 'replayed' };
}
