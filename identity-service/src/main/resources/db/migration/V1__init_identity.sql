-- Identity schema: users, their roles, and rotating refresh tokens.
-- Flyway runs with default-schema=identity; unqualified objects land in that schema.

create table users (
    id            uuid         primary key default gen_random_uuid(),
    email         varchar(255) not null,
    password_hash varchar(100) not null,
    full_name     varchar(150),
    enabled       boolean      not null default true,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),
    version       bigint       not null default 0,
    constraint uq_users_email unique (email)
);

-- RBAC: a user may hold multiple roles (element collection of an enum name).
create table user_roles (
    user_id uuid        not null references users (id) on delete cascade,
    role    varchar(50) not null,
    primary key (user_id, role)
);

-- Refresh tokens are opaque and high-entropy; only their SHA-256 hash is stored.
-- Rotation revokes the previous token and issues a new one on every use.
create table refresh_tokens (
    id         uuid         primary key default gen_random_uuid(),
    user_id    uuid         not null references users (id) on delete cascade,
    token_hash varchar(255) not null,
    expires_at timestamptz  not null,
    revoked    boolean      not null default false,
    created_at timestamptz  not null default now(),
    constraint uq_refresh_tokens_hash unique (token_hash)
);

create index idx_refresh_tokens_user_id on refresh_tokens (user_id);
create index idx_refresh_tokens_expires_at on refresh_tokens (expires_at);
