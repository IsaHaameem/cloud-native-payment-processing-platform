-- Transaction schema: double-entry ledger (accounts + balanced debit/credit entries)
-- and the idempotency record for Kafka consumption. Flyway runs with
-- default-schema=transaction; unqualified objects land in that schema.

-- Three account kinds:
--   PLATFORM_CLEARING — one singleton per currency (owner_id is null): the platform's
--     own clearing/suspense account, debit-normal (an asset — increases with debits).
--   MERCHANT_PENDING  — one per (merchant, currency): funds authorized but not yet
--     captured, credit-normal (a liability — increases with credits).
--   MERCHANT_SETTLED  — one per (merchant, currency): funds captured and owed to the
--     merchant, credit-normal.
create table accounts (
    id             uuid         primary key default gen_random_uuid(),
    account_type   varchar(30)  not null,
    -- No cross-schema FK to merchant.merchants (schema-per-service, D4) — owner_id is
    -- just the merchantId; null for the singleton PLATFORM_CLEARING account.
    owner_id       uuid,
    currency       varchar(3)   not null,
    balance_minor  bigint       not null default 0,
    created_at     timestamptz  not null default now(),
    updated_at     timestamptz  not null default now(),
    version        bigint       not null default 0
);

-- Partial uniqueness: exactly one PLATFORM_CLEARING account per currency (owner_id is
-- always null there, so a plain unique(account_type, owner_id, currency) wouldn't work —
-- Postgres treats every NULL as distinct); exactly one account per (type, merchant,
-- currency) for the merchant-owned types.
create unique index uq_accounts_platform_clearing on accounts (currency)
    where account_type = 'PLATFORM_CLEARING';
create unique index uq_accounts_merchant on accounts (account_type, owner_id, currency)
    where account_type <> 'PLATFORM_CLEARING';

-- A ledger transaction (journal entry) groups the balanced set of debit/credit legs
-- posted for one payment lifecycle event.
create table ledger_transactions (
    id          uuid         primary key default gen_random_uuid(),
    payment_id  uuid         not null,
    event_id    uuid         not null,
    event_type  varchar(50)  not null,
    description varchar(500),
    created_at  timestamptz  not null default now()
);

create index idx_ledger_transactions_payment_id on ledger_transactions (payment_id);

-- Individual debit/credit legs. Every ledger_transaction has exactly two entries whose
-- amounts match (one DEBIT, one CREDIT) — enforced in application code, not the schema.
create table ledger_entries (
    id                    uuid         primary key default gen_random_uuid(),
    ledger_transaction_id uuid         not null references ledger_transactions (id) on delete cascade,
    account_id            uuid         not null references accounts (id),
    direction             varchar(10)  not null,
    amount_minor          bigint       not null,
    currency              varchar(3)   not null,
    created_at            timestamptz  not null default now()
);

create index idx_ledger_entries_account_id on ledger_entries (account_id);
create index idx_ledger_entries_ledger_transaction_id on ledger_entries (ledger_transaction_id);

-- Consumer-side idempotency (D2): a payment.events message redelivered (at-least-once)
-- is a no-op once its eventId is already recorded here.
create table processed_events (
    id            uuid         primary key default gen_random_uuid(),
    event_id      uuid         not null,
    event_type    varchar(50)  not null,
    processed_at  timestamptz  not null default now(),
    constraint uq_processed_events_event_id unique (event_id)
);
