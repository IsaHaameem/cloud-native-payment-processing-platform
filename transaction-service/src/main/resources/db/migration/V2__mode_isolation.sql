-- M16.3: partition the double-entry ledger by test/live mode (§4.4). Additive — every
-- existing row was produced by the only mode that existed before M16, so it backfills to
-- 'live' before each column is made NOT NULL (matching EventEnvelope's null->live read).

-- accounts.mode: the clearing account is now one per (currency, mode), and each merchant
-- account one per (type, owner, currency, mode). This is what structurally isolates test
-- and live balances — a posting can only ever touch accounts in its own mode.
alter table accounts add column mode varchar(4);
update accounts set mode = 'live' where mode is null;
alter table accounts alter column mode set not null;
alter table accounts add constraint chk_accounts_mode check (mode in ('test', 'live'));

-- Uniqueness gains mode alongside the merchant/currency it already keyed on.
drop index uq_accounts_platform_clearing;
drop index uq_accounts_merchant;
create unique index uq_accounts_platform_clearing on accounts (currency, mode)
    where account_type = 'PLATFORM_CLEARING';
create unique index uq_accounts_merchant on accounts (account_type, owner_id, currency, mode)
    where account_type <> 'PLATFORM_CLEARING';

-- ledger_transactions.mode + ledger_entries.mode: denormalized onto the journal and its
-- legs (mode is derivable via account_id, but carrying it directly lets the M19 ledger/
-- balance read APIs filter by mode without a join, and keeps the schema consistent).
alter table ledger_transactions add column mode varchar(4);
update ledger_transactions set mode = 'live' where mode is null;
alter table ledger_transactions alter column mode set not null;
alter table ledger_transactions add constraint chk_ledger_transactions_mode check (mode in ('test', 'live'));

alter table ledger_entries add column mode varchar(4);
update ledger_entries set mode = 'live' where mode is null;
alter table ledger_entries alter column mode set not null;
alter table ledger_entries add constraint chk_ledger_entries_mode check (mode in ('test', 'live'));
