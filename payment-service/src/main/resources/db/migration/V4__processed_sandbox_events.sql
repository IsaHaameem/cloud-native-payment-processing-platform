-- M17.6: payment-service's first Kafka consumer role — a generic idempotent-consumer
-- dedup table (D2: at-least-once delivery), mirroring transaction-service's own
-- processed_events exactly (schema-per-service, D4). Not sandbox-specific despite the
-- filename (Flyway migration filenames are permanent once applied): the table records
-- "an event id this service has already handled," a mechanism any future consumer role
-- payment-service takes on can reuse, not a sandbox concept.

create table processed_events (
    id            uuid         primary key default gen_random_uuid(),
    event_id      uuid         not null,
    event_type    varchar(50)  not null,
    processed_at  timestamptz  not null default now(),

    constraint uq_processed_events_event_id unique (event_id)
);
