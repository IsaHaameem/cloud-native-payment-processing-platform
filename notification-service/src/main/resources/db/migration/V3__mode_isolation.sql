-- M16.6: record the test/live mode each event declared (§4.4) on notification's per-event
-- record rows.
--
-- Deliberately NULLABLE with no backfill, following audit-service's recorder semantics
-- (D126), not the ledger/analytics partitioning semantics: notification consumes a
-- mode-less stream too (identity.events — verification/password-reset emails have no mode),
-- and coercing those to 'live' would be a lie. email_log carries both payment (mode-bearing)
-- and identity (mode-less) emails; webhook_deliveries is payment-only so its mode is set in
-- practice, but stays nullable for consistency and because M18 rebuilds the webhook
-- subsystem (§4.5). The check still rejects any non-test/live string; a CHECK passes on
-- NULL, so null remains valid.
alter table email_log add column mode varchar(4);
alter table email_log add constraint chk_email_log_mode check (mode in ('test', 'live'));

alter table webhook_deliveries add column mode varchar(4);
alter table webhook_deliveries add constraint chk_webhook_deliveries_mode check (mode in ('test', 'live'));
