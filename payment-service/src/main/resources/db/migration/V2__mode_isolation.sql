-- M16.2: partition the payment data plane by test/live mode (§4.4). Additive — every
-- existing row was produced by the only mode that existed before M16, so it backfills to
-- 'live' before the column is made NOT NULL (matching EventEnvelope's null->live read).

-- payments.mode
alter table payments add column mode varchar(4);
update payments set mode = 'live' where mode is null;
alter table payments alter column mode set not null;
alter table payments add constraint chk_payments_mode check (mode in ('test', 'live'));

-- The mode-scoped reads (get/list) filter on (merchant_id, mode); the composite index
-- serves those and, by leftmost-prefix, the merchant-only lookups the V1 single-column
-- index covered — so it replaces idx_payments_merchant_id rather than supplementing it.
drop index idx_payments_merchant_id;
create index idx_payments_merchant_mode on payments (merchant_id, mode);

-- idempotency_keys.mode + mode-partitioned uniqueness: the same Idempotency-Key is a
-- distinct key per (merchant, mode), so a test retry can never replay a live response
-- (nor vice versa), while remaining unique within a given merchant+mode.
alter table idempotency_keys add column mode varchar(4);
update idempotency_keys set mode = 'live' where mode is null;
alter table idempotency_keys alter column mode set not null;
alter table idempotency_keys add constraint chk_idempotency_keys_mode check (mode in ('test', 'live'));
alter table idempotency_keys drop constraint uq_idempotency_keys_merchant_key;
alter table idempotency_keys add constraint uq_idempotency_keys_merchant_mode_key
    unique (merchant_id, mode, idempotency_key);
