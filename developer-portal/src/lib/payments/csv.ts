import type { PaymentResponse } from '@/generated/models';

/**
 * CSV export for the payments list (M23.6).
 *
 * ── It exports what is on screen, and says so ─────────────────────────────────────────
 *
 * §6.2 asks for "bulk export to CSV". There is no export endpoint in the contract and M23.6 is not
 * authorised to add one, so the rows come from the pages the list has already loaded — which makes
 * the honest description "export these rows", not "export everything matching".
 *
 * The alternative was a client that pages to the end of the result set before writing a file. On a
 * merchant with a year of payments that is hundreds of sequential requests against their own rate
 * limit, started by a button that looks instant, with no way to say how far along it is. The
 * button therefore names its count — "Export 250 rows" — and loading more rows is the user's
 * explicit act rather than a hidden one.
 *
 * ── Nothing is recomputed ─────────────────────────────────────────────────────────────
 *
 * Every column is a field the platform returned. Amounts stay in **minor units**, exactly as
 * `amountMinor` gives them, because a spreadsheet is precisely where a divided-by-100 float turns
 * into a reconciliation dispute. The header says so.
 */

/** The columns, in the order a reconciliation spreadsheet wants them. */
const COLUMNS = [
  ['id', (p: PaymentResponse) => p.id],
  ['status', (p: PaymentResponse) => p.status],
  ['amount_minor', (p: PaymentResponse) => p.amountMinor],
  ['captured_amount_minor', (p: PaymentResponse) => p.capturedAmountMinor],
  ['refunded_amount_minor', (p: PaymentResponse) => p.refundedAmountMinor],
  ['currency', (p: PaymentResponse) => p.currency],
  ['mode', (p: PaymentResponse) => p.mode],
  ['created_at', (p: PaymentResponse) => p.createdAt],
  ['description', (p: PaymentResponse) => p.description],
  ['failure_reason', (p: PaymentResponse) => p.failureReason],
  ['payment_method_token', (p: PaymentResponse) => p.paymentMethodToken],
  ['metadata', (p: PaymentResponse) => (p.metadata ? JSON.stringify(p.metadata) : undefined)],
] as const satisfies readonly (readonly [string, (p: PaymentResponse) => unknown])[];

/**
 * Escapes one value for CSV.
 *
 * ── The leading apostrophe is not decoration ──────────────────────────────────────────
 *
 * A field starting with `=`, `+`, `-` or `@` is executed as a formula by Excel and Sheets when the
 * file is opened. Metadata is arbitrary merchant-supplied text, so this file is a path from
 * whatever a merchant's own customer typed into a spreadsheet on someone's machine — CSV injection,
 * and the reason the value is prefixed rather than merely quoted. Quoting alone does not stop it.
 */
function cell(value: unknown): string {
  if (value === undefined || value === null) return '';

  const text = String(value);
  const dangerous = /^[=+\-@\t\r]/.test(text);
  const escaped = (dangerous ? `'${text}` : text).replaceAll('"', '""');

  return /[",\n\r]/.test(escaped) || dangerous ? `"${escaped}"` : escaped;
}

/**
 * @returns the CSV text for these payments, header row included.
 *
 * CRLF line endings, per RFC 4180 — the format Excel reads without a dialog.
 */
export function paymentsToCsv(payments: readonly PaymentResponse[]): string {
  const header = COLUMNS.map(([name]) => name).join(',');
  const rows = payments.map((payment) => COLUMNS.map(([, read]) => cell(read(payment))).join(','));
  return [header, ...rows].join('\r\n');
}

/**
 * A filename that says what the file is and cannot collide with yesterday's.
 *
 * The mode is in the name because a test-mode export and a live-mode export are the same columns
 * with entirely different meaning, and the two must never be confused in a downloads folder.
 */
export function csvFilename(mode: string, now: Date = new Date()): string {
  const stamp = now.toISOString().slice(0, 19).replaceAll(':', '-');
  return `paymentflow-payments-${mode}-${stamp}.csv`;
}
