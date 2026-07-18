-- Audit schema: an immutable, append-only trail of every payment lifecycle event.
-- Flyway runs with default-schema=audit; unqualified objects land in that schema.

create table audit_log (
    id             uuid        primary key default gen_random_uuid(),
    event_id       uuid        not null,
    event_type     varchar(50) not null,
    aggregate_id   varchar(100) not null,
    occurred_at    timestamptz not null,
    correlation_id varchar(100),
    payload        jsonb       not null,
    recorded_at    timestamptz not null default now(),
    constraint uq_audit_log_event_id unique (event_id)
);

create index idx_audit_log_aggregate_id on audit_log (aggregate_id);
