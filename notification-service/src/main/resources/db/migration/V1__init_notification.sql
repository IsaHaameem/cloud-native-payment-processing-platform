-- Notification schema: simulated email log + webhook delivery tracking.
-- Flyway runs with default-schema=notification; unqualified objects land in that schema.

-- Dedup gate for "has this event already been handled at all" (D2), decoupled from
-- either specific side effect (email/webhook) below — mirrors transaction-service's
-- and audit-service's identical processed_events table (M6/M7).
create table processed_events (
    id           uuid        primary key default gen_random_uuid(),
    event_id     uuid        not null,
    event_type   varchar(50) not null,
    processed_at timestamptz not null default now(),
    constraint uq_processed_events_event_id unique (event_id)
);

-- Simulated email send log (no real SMTP/SES integration in this milestone, D45) — one
-- row per event, always written (every merchant has a contact_email).
create table email_log (
    id              uuid        primary key default gen_random_uuid(),
    event_id        uuid        not null,
    merchant_id     uuid        not null,
    recipient_email varchar(255) not null,
    subject         varchar(255) not null,
    body            text        not null,
    sent_at         timestamptz not null default now(),
    constraint uq_email_log_event_id unique (event_id)
);

-- Webhook delivery tracking: one row per event that has a merchant webhook configured
-- (absent entirely if the merchant hasn't set one — nothing to retry). Status transitions
-- PENDING -> DELIVERED or PENDING -> DEAD_LETTERED after payment.events.retry exhausts
-- attempts (D46).
create table webhook_deliveries (
    id                uuid        primary key default gen_random_uuid(),
    event_id          uuid        not null,
    merchant_id       uuid        not null,
    webhook_url       varchar(2048) not null,
    payload           jsonb       not null,
    status            varchar(20) not null,
    attempt_count     int         not null default 0,
    last_attempted_at timestamptz,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    version           bigint      not null default 0,
    constraint uq_webhook_deliveries_event_id unique (event_id)
);

create index idx_webhook_deliveries_status on webhook_deliveries (status);
