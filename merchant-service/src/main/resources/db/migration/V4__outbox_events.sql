-- M15: transactional outbox for merchant.events (D3 — the platform-wide "never
-- dual-write to Postgres and Kafka" convention, mirrored exactly from payment-service's
-- outbox_events table, M5).
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
