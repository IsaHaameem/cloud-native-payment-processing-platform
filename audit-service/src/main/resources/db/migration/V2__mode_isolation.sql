-- M16.5: record the test/live mode each event declared (§4.4), feeding the M19 Events API's
-- mode filter.
--
-- Deliberately NULLABLE with no backfill, unlike the other M16 tables: audit-service is a
-- faithful, schema-agnostic recorder (D44) that consumes two streams — payment.events (which
-- carry a mode) and merchant.events (key/merchant lifecycle, mode-less). Coercing a mode-less
-- event to 'live' would be a lie in an immutable audit trail, and existing rows genuinely
-- predate mode, so null ("declared no mode") is the truthful value (D126). The check still
-- rejects any non-test/live string; a CHECK passes on NULL, so null remains valid.
alter table audit_log add column mode varchar(4);
alter table audit_log add constraint chk_audit_log_mode check (mode in ('test', 'live'));
