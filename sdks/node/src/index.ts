/**
 * The PaymentFlow SDK for Node.js and TypeScript.
 *
 * ## What this module is, as of M22.1
 *
 * The SDK's identity: which package this is, which dated API revision it was generated
 * against, which host it talks to by default, and how it identifies itself on the wire. The
 * client, the resources, the retry loop and the webhook helpers are M22.2 and later.
 *
 * ## Why the generated code is not re-exported from here
 *
 * `src/generated` is written by `:sdks:shared` from `docs/openapi.yaml` and is regenerated in
 * full every time the contract moves. Re-exporting it would make this package's public API a
 * function of a code generator's naming decisions — so a refactor of the generator, or a
 * schema renamed in a service's Java, would become a breaking change to an SDK that nobody
 * meant to break. What an integrator may rely on is decided here, deliberately, and named by
 * this file. `test/public-surface.test.mjs` asserts that rather than trusting it.
 */

import { API_VERSION as GENERATED_API_VERSION, DEFAULT_BASE_URL as GENERATED_BASE_URL } from './generated/contract.js';

/**
 * This package's own version, which moves on its own schedule.
 *
 * Deliberately *not* the API revision. §7.3 pins SDK semver as independent of the dated
 * contract version: an SDK bug fix is a patch release against an unchanged API, and a new API
 * revision does not by itself change anything about this package. Conflating them would make
 * every contract revision a major version bump and every bug fix look like an API change.
 */
export const VERSION = '0.1.0';

/**
 * The dated API revision this build was generated against, sent as `PaymentFlow-Version` on
 * every request.
 *
 * Re-exported under this SDK's own name rather than from the generated module, so that the
 * public surface stays this file's decision.
 */
export const API_VERSION: string = GENERATED_API_VERSION;

/** The host the client calls unless a `baseUrl` option overrides it. */
export const DEFAULT_BASE_URL: string = GENERATED_BASE_URL;

/**
 * How this SDK identifies itself.
 *
 * Sent on every request. It is not decoration: §7.1 notes that it is what makes SDK adoption
 * measurable in the request log M20 already records, which is the only way to know whether an
 * integrator is on a version with a known bug.
 */
export const USER_AGENT = `paymentflow-node/${VERSION} node/${process.versions.node}`;
