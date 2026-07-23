-- Sandbox schema (M17, §4.2/§4.6): the test-card catalogue. Not merchant-scoped, not
-- mode-scoped — a reference catalogue, identical for every merchant. Flyway runs with
-- default-schema=sandbox; unqualified objects land in that schema.
--
-- outcome/capture_behaviour/refund_behaviour are small closed vocabularies enforced by
-- check constraints; chk_test_cards_outcome_shape makes the catalogue's own coherence a
-- schema-level guarantee (a DECLINE row without a decline_code cannot be inserted at all).

create table test_cards (
    token               varchar(64)  primary key,
    brand               varchar(20)  not null,
    outcome             varchar(24)  not null,
    decline_code        varchar(48),
    error_code          varchar(48),
    latency_ms          int          not null default 0,
    capture_behaviour   varchar(16)  not null default 'SUCCEED',
    refund_behaviour    varchar(16)  not null default 'SUCCEED',
    deferred_delay_ms   int,
    description         text         not null,
    active              boolean      not null default true,

    constraint chk_test_cards_outcome
        check (outcome in ('APPROVE', 'DECLINE', 'ERROR', 'DELAY', 'REQUIRE_ACTION')),
    constraint chk_test_cards_capture_behaviour
        check (capture_behaviour in ('SUCCEED', 'FAIL', 'DEFER')),
    constraint chk_test_cards_refund_behaviour
        check (refund_behaviour in ('SUCCEED', 'FAIL')),
    constraint chk_test_cards_latency_bounded
        check (latency_ms >= 0 and latency_ms <= 10000),
    -- The catalogue cannot be seeded into an incoherent state: a DECLINE row without a
    -- decline_code, an ERROR row without an error_code, or a DELAY row without a
    -- deferred delay are all schema-rejected, not just application-validated.
    constraint chk_test_cards_outcome_shape check (
        (outcome <> 'DECLINE' or decline_code is not null)
        and (outcome <> 'ERROR' or error_code is not null)
        and (outcome <> 'DELAY' or deferred_delay_ms is not null)
    )
);

create index idx_test_cards_active on test_cards (active) where active;
