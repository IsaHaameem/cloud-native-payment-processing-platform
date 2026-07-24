-- M17.6: the delayed-outcome scheduler's own outbox (§4.2) — mirrors payment-service's
-- transactional-outbox shape (D3) exactly: a row written in the same transaction as the
-- decision that scheduled it (SandboxDecisionService), polled by ScheduledOutcomeRelay,
-- published to `sandbox.scheduled.events`, then marked delivered. delivered_at IS NULL
-- is the "still pending" query, the same predicate payment-service's own outbox uses.
--
-- Only ever written for CAPTURE today (pm_card_delayedSettlement, the DELAY_SETTLEMENT
-- override) — operation/outcome are still modeled generically, matching
-- DecisionOutcome's own "define the vocabulary now" discipline, since the
-- infrastructure itself has no reason to be capture-specific.

create table scheduled_outcomes (
    id             uuid         primary key default gen_random_uuid(),
    payment_id     uuid         not null,
    merchant_id    uuid         not null,
    mode           varchar(4)   not null,
    operation      varchar(16)  not null,
    outcome        varchar(24)  not null,
    fire_at        timestamptz  not null,
    delivered_at   timestamptz,
    created_at     timestamptz  not null default now(),

    constraint chk_scheduled_outcomes_mode check (mode = 'test'),
    constraint chk_scheduled_outcomes_operation check (operation in ('AUTHORIZE', 'CAPTURE', 'REFUND')),
    constraint chk_scheduled_outcomes_outcome
        check (outcome in ('APPROVE', 'DECLINE', 'ERROR', 'DELAY', 'REQUIRE_ACTION'))
);

-- Serves the relay's hot-path poll: due, undelivered rows in fire order.
create index idx_scheduled_outcomes_pending on scheduled_outcomes (fire_at) where delivered_at is null;
