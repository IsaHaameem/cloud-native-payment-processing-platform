-- M16.4: partition the per-merchant aggregate read model by test/live mode (§4.4).
-- Additive — every existing row was produced by the only mode that existed before M16,
-- so it backfills to 'live' before the column is made NOT NULL (matching EventEnvelope's
-- null->live read).

alter table merchant_payment_stats add column mode varchar(4);
update merchant_payment_stats set mode = 'live' where mode is null;
alter table merchant_payment_stats alter column mode set not null;
alter table merchant_payment_stats add constraint chk_merchant_payment_stats_mode check (mode in ('test', 'live'));

-- One aggregate row per (merchant, currency, mode): test and live counts/totals are
-- structurally separate, never mixed. Uniqueness gains mode alongside merchant+currency.
alter table merchant_payment_stats drop constraint uq_merchant_payment_stats_merchant_currency;
alter table merchant_payment_stats add constraint uq_merchant_payment_stats_merchant_currency_mode
    unique (merchant_id, currency, mode);
