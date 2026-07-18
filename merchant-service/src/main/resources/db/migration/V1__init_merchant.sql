-- Merchant schema: merchant business profiles and their API keys.
-- Flyway runs with default-schema=merchant; unqualified objects land in that schema.

create table merchants (
    id            uuid         primary key default gen_random_uuid(),
    -- No cross-schema FK to identity.users (schema-per-service, D4) — the owning
    -- user's identity is validated by the JWT at request time, not by the database.
    owner_user_id uuid         not null,
    business_name varchar(200) not null,
    contact_email varchar(255) not null,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),
    version       bigint       not null default 0,
    constraint uq_merchants_owner_user_id unique (owner_user_id)
);

-- API keys are opaque and high-entropy; only their SHA-256 hash is stored.
-- Rotation revokes the previous key and issues a new one; only one is active at a time.
create table api_keys (
    id          uuid         primary key default gen_random_uuid(),
    merchant_id uuid         not null references merchants (id) on delete cascade,
    key_prefix  varchar(16)  not null,
    key_hash    varchar(255) not null,
    revoked_at  timestamptz,
    created_at  timestamptz  not null default now(),
    constraint uq_api_keys_hash unique (key_hash)
);

create index idx_api_keys_merchant_id on api_keys (merchant_id);
-- Partial index: the lookup that matters at runtime is always "the merchant's one
-- active key", never revoked history.
create unique index uq_api_keys_active_per_merchant on api_keys (merchant_id) where revoked_at is null;
