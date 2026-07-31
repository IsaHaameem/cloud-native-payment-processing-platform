/**
 * `client.testHelpers` — the sandbox controls (M22.3).
 *
 * Six operations that only exist in test mode. They are grouped under one namespace rather
 * than scattered across `cards`, `decisions` and `simulations` because what they have in
 * common is the thing worth signalling at the call site: none of this works with a live key,
 * and code that reaches for it is test-mode code.
 *
 * The mode is decided by the key alone (§9). This SDK has no `mode` option and will not get
 * one — an SDK switch that appeared to move a client between test and live would be a lie,
 * because the platform ignores everything except which key was presented.
 */

import { OPERATIONS } from '../generated/operations.js';
import type {
  DecisionLogEntryResponse,
  SimulationOverrideResponse,
  TestCardResponse,
} from '../generated/models.js';
import type { RequestOptions } from '../transport.js';
import type { OffsetPage } from '../pagination.js';
import { Resource } from './base.js';

/** What `testHelpers.createSimulationOverride` accepts. */
export interface SimulationOverrideCreateParams {
  /** Which behaviour to force, such as `DECLINE` or `LATENCY`. */
  readonly scenario: string;
  /** The decline code to return, for a declining scenario. */
  readonly declineCode?: string;
  /** The error code to return, for a failing scenario. */
  readonly errorCode?: string;
  /** How long to delay each authorization, for a latency scenario. */
  readonly latencyMs?: number;
  /** How many authorizations the override applies to before expiring. */
  readonly remainingCount?: number;
  /** How long the override lives, in seconds. */
  readonly durationSeconds?: number;
}

/** What `testHelpers.listDecisions` accepts. Offset pagination, per D139. */
export type DecisionListParams = {
  readonly page?: number;
  readonly size?: number;
  readonly sort?: readonly string[];
};

export class TestHelpers extends Resource {
  /**
   * Lists the seeded test cards and what each one does.
   *
   * Returns a plain array, because the endpoint does — this catalogue is small and fixed, and
   * is not paginated on the wire.
   */
  listCards(options?: RequestOptions): Promise<TestCardResponse[]> {
    return this.send<TestCardResponse[]>(OPERATIONS.listTestCards, { options });
  }

  /** Lists authorization decisions the sandbox made, and why. */
  listDecisions(
    params: DecisionListParams = {},
    options?: RequestOptions,
  ): Promise<OffsetPage<DecisionLogEntryResponse>> {
    return this.listOffset<DecisionLogEntryResponse>(OPERATIONS.listSandboxDecisions, params, options);
  }

  /**
   * Lists the decisions made for one payment.
   *
   * A plain array rather than a page: one payment's decisions are few and the endpoint returns
   * them all.
   */
  listDecisionsForPayment(paymentId: string, options?: RequestOptions): Promise<DecisionLogEntryResponse[]> {
    return this.send<DecisionLogEntryResponse[]>(OPERATIONS.listSandboxDecisionsForPayment, {
      path: { paymentId },
      options,
    });
  }

  /** Forces a behaviour for subsequent authorizations, replacing any active override. */
  createSimulationOverride(
    params: SimulationOverrideCreateParams,
    options?: RequestOptions,
  ): Promise<SimulationOverrideResponse> {
    return this.send<SimulationOverrideResponse>(OPERATIONS.createSimulationOverride, { body: params, options });
  }

  /** Retrieves the active override. */
  retrieveActiveSimulationOverride(options?: RequestOptions): Promise<SimulationOverrideResponse> {
    return this.send<SimulationOverrideResponse>(OPERATIONS.getActiveSimulationOverride, { options });
  }

  /** Revokes the active override. Resolves with nothing: the API returns 204. */
  revokeActiveSimulationOverride(options?: RequestOptions): Promise<void> {
    return this.send<void>(OPERATIONS.revokeActiveSimulationOverride, { options });
  }
}
