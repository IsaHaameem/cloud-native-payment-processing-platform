-- M17.5: per-merchant, per-mode simulation overrides (§8.2) — the "chaos knob" a
-- developer sets to force the next N requests (or the next N seconds) to fail a
-- particular way. Only ever consulted for test mode (§7's mode-isolation barrier);
-- DecisionEngine.decideLive has no parameter that could accept one, and this table's
-- own chk_simulation_overrides_mode constraint makes a live-mode row impossible at the
-- schema level too — structural, not just a runtime check.
--
-- scenario is the full 8-value control-API vocabulary (§8.2), wider than
-- DecisionEngine's own 6-value OverrideScenario: duplicate_webhooks/webhook_failure are
-- validated and stored here but never reach the engine (D131) — M18's delivery
-- pipeline is their only reader.
--
-- At most one override is active per (merchant_id, mode) at a time: the control API
-- revokes any still-active override for that pair before inserting a new one
-- (application-enforced), and uq_simulation_overrides_active makes it a hard schema
-- guarantee too, not just application discipline.

create table simulation_overrides (
    id                 uuid         primary key default gen_random_uuid(),
    merchant_id        uuid         not null,
    mode               varchar(4)   not null,
    scenario           varchar(24)  not null,
    decline_code       varchar(48),
    error_code         varchar(48),
    latency_ms         int,
    remaining_count    int,
    expires_at         timestamptz,
    revoked_at         timestamptz,
    created_at         timestamptz  not null default now(),

    -- Only test mode is developer-controllable (§7) — a live-mode row can never be
    -- inserted, regardless of what the application layer does or fails to check.
    constraint chk_simulation_overrides_mode check (mode = 'test'),
    constraint chk_simulation_overrides_scenario check (scenario in (
        'FORCE_DECLINE', 'FORCE_ERROR', 'INJECT_LATENCY', 'FORCE_TIMEOUT', 'FORCE_RATE_LIMIT',
        'DELAY_SETTLEMENT', 'DUPLICATE_WEBHOOKS', 'WEBHOOK_FAILURE')),
    constraint chk_simulation_overrides_latency_bounded
        check (latency_ms is null or (latency_ms >= 0 and latency_ms <= 10000)),
    -- >= 0, not > 0: a count-bounded override's remaining_count legitimately reaches 0
    -- once exhausted (D127's atomic decrement produces exactly that row) — only a
    -- negative value is actually invalid. Requiring a *positive* count at creation time
    -- is OverrideService's job (application-layer validation), not this constraint's.
    constraint chk_simulation_overrides_remaining_count_not_negative
        check (remaining_count is null or remaining_count >= 0),
    -- The catalogue's own "coherent shape" discipline (V1__init_sandbox.sql), applied to
    -- overrides: a FORCE_DECLINE row without a decline_code, or a FORCE_ERROR row
    -- without an error_code, cannot be inserted.
    constraint chk_simulation_overrides_shape check (
        (scenario <> 'FORCE_DECLINE' or decline_code is not null)
        and (scenario <> 'FORCE_ERROR' or error_code is not null)
        and (scenario not in ('INJECT_LATENCY', 'DELAY_SETTLEMENT') or latency_ms is not null)
    ),
    -- "Next N requests OR for a duration" (§8.2) — at least one bound must be set, or
    -- the override would never expire.
    constraint chk_simulation_overrides_bounded
        check (remaining_count is not null or expires_at is not null)
);

create unique index uq_simulation_overrides_active on simulation_overrides (merchant_id, mode) where revoked_at is null;

-- Additive: decision_log's override_id column has existed since M17.2 (V3) with no FK,
-- because simulation_overrides didn't exist yet. It does now.
alter table decision_log
    add constraint fk_decision_log_override foreign key (override_id) references simulation_overrides (id);
