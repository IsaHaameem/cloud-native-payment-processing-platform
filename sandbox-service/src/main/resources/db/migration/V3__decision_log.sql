-- M17.2: the append-only decision log — both the audit trail behind the dashboard's
-- "why was this declined?" panel (M17.8) and the idempotency store for the advisory
-- call itself (D128): decision_key is unique, so a retried call with the same key
-- finds its own prior row instead of evaluating (and, from M17.5, consuming an
-- override) a second time.
--
-- override_id has no foreign key yet — simulation_overrides doesn't exist until
-- M17.5. Every row through M17.4 has override_id = null; M17.5 adds the FK
-- additively once the referenced table exists.

create table decision_log (
    id                    uuid         primary key default gen_random_uuid(),
    decision_key          varchar(128) not null,
    merchant_id           uuid         not null,
    mode                  varchar(4)   not null,
    payment_id            uuid         not null,
    operation             varchar(16)  not null,
    payment_method_token  varchar(64),
    amount_minor          bigint       not null,
    currency              varchar(3)   not null,
    outcome               varchar(24)  not null,
    decline_code          varchar(48),
    error_code            varchar(48),
    latency_ms            int          not null default 0,
    source                varchar(16)  not null,
    override_id           uuid,
    deferred_operation    varchar(16),
    deferred_delay_ms     int,
    correlation_id        varchar(64),
    created_at            timestamptz  not null default now(),

    constraint uq_decision_log_decision_key unique (decision_key),
    constraint chk_decision_log_mode check (mode in ('test', 'live')),
    constraint chk_decision_log_operation check (operation in ('AUTHORIZE', 'CAPTURE', 'REFUND')),
    constraint chk_decision_log_outcome
        check (outcome in ('APPROVE', 'DECLINE', 'ERROR', 'DELAY', 'REQUIRE_ACTION')),
    constraint chk_decision_log_source check (source in ('OVERRIDE', 'TEST_CARD', 'MODE_DEFAULT', 'ACQUIRER')),
    constraint chk_decision_log_deferred_operation
        check (deferred_operation is null or deferred_operation in ('AUTHORIZE', 'CAPTURE', 'REFUND'))
);

-- Serves the dashboard's decision-log panel (M17.8): most recent decisions for a
-- merchant, scoped to the mode the caller is authenticated in.
create index idx_decision_log_merchant_mode_time on decision_log (merchant_id, mode, created_at desc);
