/**
 * The only places a number becomes a string (M23.1).
 *
 * Money is integer minor units everywhere in this platform — §15 invariant 7 — and the single
 * most likely way for a portal to break that is a component doing `amount / 100` inline. It
 * happens once, here, and every screen goes through it.
 */

/**
 * Currencies whose smallest unit *is* the unit. Dividing a JPY amount by 100 renders ¥12.00
 * for a ¥1,200 charge — off by two orders of magnitude, and plausible enough to ship.
 */
const ZERO_DECIMAL = new Set(['JPY', 'KRW', 'VND', 'CLP', 'ISK', 'XOF', 'XAF', 'XPF']);
const THREE_DECIMAL = new Set(['BHD', 'JOD', 'KWD', 'OMR', 'TND']);

function fractionDigits(currency: string): number {
  const code = currency.toUpperCase();
  if (ZERO_DECIMAL.has(code)) return 0;
  if (THREE_DECIMAL.has(code)) return 3;
  return 2;
}

/** Renders `amountMinor` in `currency`, e.g. `formatMoney(4000, 'EUR')` → `€40.00`. */
export function formatMoney(amountMinor: number, currency: string, locale = 'en-US'): string {
  const digits = fractionDigits(currency);
  return new Intl.NumberFormat(locale, {
    style: 'currency',
    currency: currency.toUpperCase(),
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(amountMinor / 10 ** digits);
}

/** An absolute timestamp, for tables and detail pages. */
export function formatDateTime(value: string | Date, locale = 'en-US'): string {
  const date = typeof value === 'string' ? new Date(value) : value;
  return new Intl.DateTimeFormat(locale, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date);
}

/**
 * A relative timestamp, for "when did this happen" at a glance. Falls back to an absolute date
 * past a week, because "38 days ago" is harder to reason about than the date itself.
 */
export function formatRelativeTime(value: string | Date, now: Date = new Date()): string {
  const date = typeof value === 'string' ? new Date(value) : value;
  const seconds = Math.round((date.getTime() - now.getTime()) / 1000);
  const absolute = Math.abs(seconds);

  if (absolute > 7 * 86_400) return formatDateTime(date);

  const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ['day', 86_400],
    ['hour', 3_600],
    ['minute', 60],
  ];
  const formatter = new Intl.RelativeTimeFormat('en-US', { numeric: 'auto' });
  for (const [unit, size] of units) {
    if (absolute >= size) return formatter.format(Math.round(seconds / size), unit);
  }
  return formatter.format(Math.round(seconds), 'second');
}

/**
 * Shortens an object id for a dense column while keeping both ends, because the prefix says
 * what it is (`pay_`, `re_`, `evt_`) and the tail is what distinguishes two of them.
 */
export function truncateId(id: string, tail = 6): string {
  const underscore = id.indexOf('_');
  const prefix = underscore > 0 ? id.slice(0, underscore + 1) : '';
  const body = id.slice(prefix.length);
  if (body.length <= tail + 2) return id;
  return `${prefix}…${body.slice(-tail)}`;
}
