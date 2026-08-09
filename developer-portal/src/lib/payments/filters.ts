/**
 * The payments list's filter state, and the two things it has to be (M23.6).
 *
 * ── The URL is the view ───────────────────────────────────────────────────────────────
 *
 * §6.2 asks for "saved views persisted per user", and the platform has no place to persist a user
 * preference — no preferences entity exists in identity-service or merchant-service, and M23.6 is
 * not authorised to invent one. So a view is its URL: every filter round-trips through the query
 * string, which makes it bookmarkable, shareable, reloadable and back-button-correct without
 * storing anything anywhere.
 *
 * That is a smaller claim than "per user" and a more useful object than a private preset. A
 * developer investigating a spike pastes the link into a ticket and their colleague sees the same
 * rows. `localStorage` presets would have been per *browser* — invisible on another machine — and
 * would have added client state to a screen that otherwise needs none.
 *
 * ── Two vocabularies, deliberately kept apart ─────────────────────────────────────────
 *
 * The URL speaks the *contract's* language (`amount_min`, `created_after`), so a link is
 * self-describing to anyone who has read the API docs, and the mapping to platform parameters is
 * a rename rather than a translation. `PaymentFilters` is the portal's shape, with the range
 * fields as the pairs a UI actually edits.
 *
 * ── Nothing here validates on the platform's behalf ───────────────────────────────────
 *
 * Bad input is dropped, never corrected. An `amount_min` of `"abc"` disappears rather than
 * becoming `0` — a filter that silently means something other than what the URL says is worse
 * than one that is not applied, because the first misleads and the second is visible.
 */

/**
 * The statuses a payment can hold.
 *
 * Taken from `PaymentStatus`, whose `wireName()` is lowercase since revision `2026-08-01`. The
 * contract types `status` as an open string precisely because new values may appear without a new
 * revision, so this list is what the filter *offers*, never what the screen is willing to render:
 * an unrecognised status coming back from the platform is displayed as itself.
 */
export const PAYMENT_STATUSES = [
  'created',
  'authorized',
  'captured',
  'partially_refunded',
  'refunded',
  'failed',
  'voided',
] as const;

export type PaymentStatusValue = (typeof PAYMENT_STATUSES)[number];

/** Currencies offered in the picker. The field accepts any ISO 4217 code the platform returns. */
export const COMMON_CURRENCIES = ['USD', 'EUR', 'GBP', 'JPY', 'CAD', 'AUD'] as const;

export interface PaymentFilters {
  /** Empty means "every status", which is what omitting the parameter does. */
  readonly statuses: readonly string[];
  readonly currency: string | undefined;
  /** Minor units, as the API takes them — no decimal conversion happens in the URL. */
  readonly amountMin: number | undefined;
  readonly amountMax: number | undefined;
  /** RFC 3339, as `created_after`/`created_before` require. */
  readonly createdAfter: string | undefined;
  readonly createdBefore: string | undefined;
  /** `metadata[key]=value`. One pair; the API accepts an object and the UI offers one row. */
  readonly metadataKey: string | undefined;
  readonly metadataValue: string | undefined;
}

export const NO_FILTERS: PaymentFilters = {
  statuses: [],
  currency: undefined,
  amountMin: undefined,
  amountMax: undefined,
  createdAfter: undefined,
  createdBefore: undefined,
  metadataKey: undefined,
  metadataValue: undefined,
};

/** Whether anything is narrowing the list — the difference between "no payments" and "no matches". */
export function isFiltered(filters: PaymentFilters): boolean {
  return (
    filters.statuses.length > 0 ||
    filters.currency !== undefined ||
    filters.amountMin !== undefined ||
    filters.amountMax !== undefined ||
    filters.createdAfter !== undefined ||
    filters.createdBefore !== undefined ||
    filters.metadataKey !== undefined
  );
}

/** How many distinct filters are applied, for the count on the filter button. */
export function activeFilterCount(filters: PaymentFilters): number {
  return (
    filters.statuses.length +
    (filters.currency ? 1 : 0) +
    (filters.amountMin !== undefined || filters.amountMax !== undefined ? 1 : 0) +
    (filters.createdAfter !== undefined || filters.createdBefore !== undefined ? 1 : 0) +
    (filters.metadataKey ? 1 : 0)
  );
}

/**
 * Reads filters out of a URL.
 *
 * Every field is optional and every malformed one is dropped. `status` is comma-separated because
 * the API takes a single `status` parameter and a repeated key would be ambiguous about whether
 * the platform ORs or overwrites — one value is sent (see `toPlatformParams`), and the extra
 * selections are kept in the URL so the UI can show what was asked for.
 */
export function filtersFromSearchParams(params: URLSearchParams): PaymentFilters {
  const statuses = (params.get('status') ?? '')
    .split(',')
    .map((value) => value.trim().toLowerCase())
    .filter((value) => value.length > 0);

  const currency = (params.get('currency') ?? '').trim().toUpperCase();
  const metadataKey = (params.get('metadata_key') ?? '').trim();
  const metadataValue = (params.get('metadata_value') ?? '').trim();

  return {
    statuses,
    currency: /^[A-Z]{3}$/.test(currency) ? currency : undefined,
    amountMin: wholeNumber(params.get('amount_min')),
    amountMax: wholeNumber(params.get('amount_max')),
    createdAfter: timestamp(params.get('created_after')),
    createdBefore: timestamp(params.get('created_before')),
    metadataKey: metadataKey.length > 0 ? metadataKey : undefined,
    // A key with no value is a real filter — "has this key at all" is how the API reads an empty
    // value — so the value is allowed to be blank while the key is not.
    metadataValue: metadataKey.length > 0 ? metadataValue : undefined,
  };
}

/**
 * Writes filters back into a query string.
 *
 * Absent rather than empty: `?status=&currency=` is four characters of noise in a link somebody
 * is meant to read, and it makes two identical views compare unequal.
 */
export function searchParamsFromFilters(filters: PaymentFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.statuses.length > 0) params.set('status', filters.statuses.join(','));
  if (filters.currency) params.set('currency', filters.currency);
  if (filters.amountMin !== undefined) params.set('amount_min', String(filters.amountMin));
  if (filters.amountMax !== undefined) params.set('amount_max', String(filters.amountMax));
  if (filters.createdAfter) params.set('created_after', filters.createdAfter);
  if (filters.createdBefore) params.set('created_before', filters.createdBefore);
  if (filters.metadataKey) {
    params.set('metadata_key', filters.metadataKey);
    if (filters.metadataValue) params.set('metadata_value', filters.metadataValue);
  }
  return params;
}

/**
 * The parameters `listPayments` is actually called with.
 *
 * ── Only what the descriptor names ────────────────────────────────────────────────────
 *
 * `/api/platform/[operation]` rejects any parameter the operation does not document, before a
 * request is built. That is a feature to build against rather than around: this function emits
 * exactly `status`, `currency`, `amount_min`, `amount_max`, `created_after`, `created_before`,
 * `metadata` and `limit`, and anything else would be a 400 the moment it was added.
 *
 * ── Mode and merchant are conspicuously absent ────────────────────────────────────────
 *
 * Neither is a parameter of this operation and neither may be: the gateway derives the merchant
 * from the session's JWT, and `callAs` supplies the mode from the sealed cookie (D184/D187).
 * A filter object that could carry either would be a way for the browser to ask for someone
 * else's rows, which is why this type has no field for them.
 *
 * ── One status per request ────────────────────────────────────────────────────────────
 *
 * The contract types `status` as a single string, so multiple selections cannot be sent as one
 * query. The first is sent and the rest are applied client-side by `matchesClientSide` — narrowing
 * a server-narrowed page, never widening it, so no row appears that the platform did not return
 * for this merchant and mode.
 */
export function toPlatformParams(
  filters: PaymentFilters,
  limit: number,
): Record<string, string | number> {
  const params: Record<string, string | number> = { limit };

  if (filters.statuses.length > 0) params.status = filters.statuses[0] as string;
  if (filters.currency) params.currency = filters.currency;
  if (filters.amountMin !== undefined) params.amount_min = filters.amountMin;
  if (filters.amountMax !== undefined) params.amount_max = filters.amountMax;
  if (filters.createdAfter) params.created_after = filters.createdAfter;
  if (filters.createdBefore) params.created_before = filters.createdBefore;
  if (filters.metadataKey) {
    // `style: deepObject` — the contract's own spelling for a map-valued query parameter.
    params[`metadata[${filters.metadataKey}]`] = filters.metadataValue ?? '';
  }

  return params;
}

/**
 * Whether extra status selections beyond the first are being applied in the browser.
 *
 * Surfaced on screen rather than hidden, because a filter applied to *the page that arrived* is
 * not the same promise as one applied to the whole result set: with several statuses selected, a
 * page can come back with fewer visible rows than the platform sent.
 */
export function hasClientSideNarrowing(filters: PaymentFilters): boolean {
  return filters.statuses.length > 1;
}

/** @returns whether a payment survives the selections the server could not express. */
export function matchesClientSide(
  filters: PaymentFilters,
  payment: { status?: string | undefined },
): boolean {
  if (filters.statuses.length <= 1) return true;
  return filters.statuses.includes((payment.status ?? '').toLowerCase());
}

function wholeNumber(raw: string | null): number | undefined {
  if (raw === null || raw.trim().length === 0) return undefined;
  const value = Number(raw);
  return Number.isInteger(value) && value >= 0 ? value : undefined;
}

/**
 * @returns the value only if it is a timestamp the API will accept.
 *
 * `Date.parse` is the check rather than a regex: `created_after` is documented as RFC 3339, and an
 * unparseable value sent anyway would come back as a 400 the user cannot act on.
 */
function timestamp(raw: string | null): string | undefined {
  if (raw === null || raw.trim().length === 0) return undefined;
  return Number.isNaN(Date.parse(raw)) ? undefined : raw.trim();
}
