-- M15: rebuilds api_keys for the multi-key, scoped, mode-aware model (D99 supersedes
-- D29's single-active-key constraint). Additive-only against existing data: every
-- existing key backfills to a sensible default rather than being dropped, since V1's
-- keys — while never actually used to authenticate anything (D31/D32) — still belong
-- to a real merchant record.

-- The single-active-key invariant no longer holds: a merchant may now have many
-- concurrently active keys (one per type/mode combination, typically, but not
-- enforced as such — a developer may create as many named keys as they want).
drop index if exists uq_api_keys_active_per_merchant;

alter table api_keys
    add column mode              varchar(10)  not null default 'LIVE',
    add column key_type          varchar(20)  not null default 'SECRET',
    add column name              varchar(100),
    add column scopes            text[]       not null default '{}',
    add column last_used_at      timestamptz,
    add column expires_at        timestamptz,
    add column grace_expires_at  timestamptz;

-- Backfill: every pre-M15 key was full-access and untyped-by-mode, so LIVE/SECRET/'*'
-- is the honest description of what it already was, not a guess. Defaults above cover
-- new rows too, but are dropped below once backfill is complete, so future inserts
-- must supply these values explicitly (matches ApiKeyService's new issue() contract).
update api_keys set name = 'Legacy key', scopes = '{*}' where name is null;

alter table api_keys
    alter column mode set default null,
    alter column key_type set default null;

alter table api_keys
    add constraint chk_api_keys_mode check (mode in ('TEST', 'LIVE')),
    add constraint chk_api_keys_key_type check (key_type in ('PUBLISHABLE', 'SECRET'));

-- The lookup that matters at runtime is now "does this merchant have this key active
-- in this mode", not "the merchant's one active key" (which no longer exists).
create index idx_api_keys_merchant_id_mode on api_keys (merchant_id, mode);
