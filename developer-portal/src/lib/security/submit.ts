/**
 * Submitting a form that a menu item cannot submit for itself (M23.2).
 *
 * Radix unmounts a menu's content as part of selecting an item, which cancels the click's default
 * action — so a submit button inside a menu never submits, with or without a `form` attribute.
 * Both arrangements were measured producing no request at all. See `account-menu.tsx`.
 *
 * `requestSubmit` rather than `submit`: it runs constraint validation and fires the `submit`
 * event, so the form behaves exactly as if a user had pressed a button in it. `form.submit()`
 * bypasses both, which is the kind of difference that only shows up once a form has validation.
 */
export function submitById(formId: string): void {
  const form = document.getElementById(formId);
  if (form instanceof HTMLFormElement) form.requestSubmit();
}
