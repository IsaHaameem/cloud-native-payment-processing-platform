-- Payment schema: payments (the FSM aggregate), idempotency records, and the
-- transactional outbox. Flyway runs with default-schema=payment; unqualified
-- objects land in that schema.

create table payments (
    id                     uuid         primary key default gen_random_uuid(),
    -- No cross-schema FK to merchant.merchants (schema-per-service, D4) — ownership
    -- is resolved at request time via merchant-service (OpenFeign), not a DB join.
    merchant_id            uuid         not null,
    amount_minor           bigint       not null,
    -- varchar, not char: Hibernate's schema validator maps a plain JPA String to
    -- VARCHAR regardless of columnDefinition overrides (a known rough edge validating
    -- fixed-length CHAR columns); functionally identical for an always-exactly-3-char
    -- currency code, so this is the pragmatic choice over fighting the validator.
    currency               varchar(3)   not null,
    status                 varchar(30)  not null,
    captured_amount_minor  bigint       not null default 0,
    refunded_amount_minor  bigint       not null default 0,
    description            varchar(500),
    failure_reason         varchar(500),
    created_at             timestamptz  not null default now(),
    updated_at             timestamptz  not null default now(),
    version                bigint       not null default 0
);

create index idx_payments_merchant_id on payments (merchant_id);

-- Idempotency (§5): one row per (merchant, Idempotency-Key). A replayed request with
-- the same key returns the stored response instead of reprocessing; the same key
-- reused with a different request body (fingerprint mismatch) is rejected.
create table idempotency_keys (
    id                   uuid         primary key default gen_random_uuid(),
    merchant_id          uuid         not null,
    idempotency_key      varchar(255) not null,
    request_fingerprint  varchar(64)  not null,
    response_status      integer      not null,
    response_body        text         not null,
    created_at           timestamptz  not null default now(),
    constraint uq_idempotency_keys_merchant_key unique (merchant_id, idempotency_key)
);

-- Transactional outbox (D3): written in the same transaction as the payment mutation
-- it records; a separate polling relay publishes to Kafka and marks published_at.
create table outbox_events (
    id            uuid         primary key default gen_random_uuid(),
    aggregate_id  uuid         not null,
    event_type    varchar(100) not null,
    topic         varchar(150) not null,
    payload       text         not null,
    created_at    timestamptz  not null default now(),
    published_at  timestamptz
);

-- Partial index: the relay only ever queries the unpublished tail, oldest first.
create index idx_outbox_events_unpublished on outbox_events (created_at) where published_at is null;
