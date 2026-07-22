-- M15: self-serve signup completion (task 11) — email verification and password
-- reset, both opaque-hashed-token tables mirroring refresh_tokens' existing shape
-- (D16): the raw token is handed out once (embedded in a link), only its SHA-256
-- hash is ever persisted.

alter table users add column email_verified_at timestamptz;

create table email_verifications (
    id         uuid        primary key default gen_random_uuid(),
    user_id    uuid        not null references users (id) on delete cascade,
    token_hash varchar(255) not null,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    created_at timestamptz not null default now(),
    constraint uq_email_verifications_hash unique (token_hash)
);

create index idx_email_verifications_user_id on email_verifications (user_id);

create table password_resets (
    id         uuid        primary key default gen_random_uuid(),
    user_id    uuid        not null references users (id) on delete cascade,
    token_hash varchar(255) not null,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    created_at timestamptz not null default now(),
    constraint uq_password_resets_hash unique (token_hash)
);

create index idx_password_resets_user_id on password_resets (user_id);

-- M15: transactional outbox for identity.events (D3, mirrored from payment-service's
-- M5 / merchant-service's M15 pattern) — identity-service becomes a Kafka producer
-- for the first time.
create table outbox_events (
    id            uuid         primary key default gen_random_uuid(),
    aggregate_id  uuid         not null,
    event_type    varchar(100) not null,
    topic         varchar(150) not null,
    payload       text         not null,
    created_at    timestamptz  not null default now(),
    published_at  timestamptz
);

create index idx_outbox_events_unpublished on outbox_events (created_at) where published_at is null;
