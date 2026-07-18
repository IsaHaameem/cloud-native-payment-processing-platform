-- Analytics schema: per-merchant/currency payment aggregates for reporting.
-- Flyway runs with default-schema=analytics; unqualified objects land in that schema.

create table processed_events (
    id           uuid        primary key default gen_random_uuid(),
    event_id     uuid        not null,
    event_type   varchar(50) not null,
    processed_at timestamptz not null default now(),
    constraint uq_processed_events_event_id unique (event_id)
);

create table merchant_payment_stats (
    id                       uuid        primary key default gen_random_uuid(),
    merchant_id              uuid        not null,
    currency                 varchar(3)  not null,
    created_count            bigint      not null default 0,
    authorized_count         bigint      not null default 0,
    captured_count           bigint      not null default 0,
    refunded_count           bigint      not null default 0,
    voided_count             bigint      not null default 0,
    total_captured_amount_minor bigint   not null default 0,
    total_refunded_amount_minor bigint   not null default 0,
    created_at               timestamptz not null default now(),
    updated_at               timestamptz not null default now(),
    version                  bigint      not null default 0,
    constraint uq_merchant_payment_stats_merchant_currency unique (merchant_id, currency)
);
