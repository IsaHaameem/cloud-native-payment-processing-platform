-- M20.4: usage aggregates and the retention machinery (§5/M20 task 5, D116).
--
-- D116 decided that a high-volume log table without a retention story is a scheduled
-- outage, and that the pruner ships in the same milestone as the log rather than "when it
-- becomes a problem". This migration is the other half of V4: what survives after the raw
-- rows are dropped.

-- One row per (merchant, key, mode, day, route). Deliberately keyed by *route* rather than
-- raw path: /v1/payments/<uuid> is a different string for every payment, so aggregating on
-- the raw path would produce one row per request and defeat the entire point of the table.
-- The rollup normalises ids out of the path before grouping.
create table api_usage_daily (
    id                  uuid        primary key default gen_random_uuid(),
    merchant_id         uuid        not null,
    -- Nullable: §5/M20 asks for usage "per key", but a key can be revoked and deleted while
    -- its traffic remains a fact about the merchant's day.
    key_id              uuid,
    mode                varchar(4)  not null,
    day                 date        not null,
    route               text        not null,

    request_count       bigint      not null default 0,
    -- Split rather than one error_count: 4xx is the developer's problem and 5xx is ours, and
    -- a single number cannot answer "is my integration broken or is the platform?".
    client_error_count  bigint      not null default 0,
    server_error_count  bigint      not null default 0,

    -- Sum plus count gives an exact mean after the raw rows are gone.
    total_duration_ms   bigint      not null default 0,
    max_duration_ms     bigint      not null default 0,
    -- Percentiles are computed once, at rollup time, from the raw rows while they still
    -- exist — percentiles cannot be averaged or recombined afterwards, so computing them
    -- later from these aggregates would be arithmetically impossible, not merely lossy.
    p50_duration_ms     bigint,
    p95_duration_ms     bigint,
    p99_duration_ms     bigint,

    created_at          timestamptz not null default now(),

    constraint chk_api_usage_daily_mode check (mode in ('test', 'live')),
    -- `nulls not distinct` (Postgres 15+) is load-bearing here rather than stylistic: key_id
    -- is nullable, and under the default `nulls distinct` two rollups of the same
    -- keyless day would both be accepted, silently double-counting usage.
    constraint uq_api_usage_daily unique nulls not distinct (merchant_id, key_id, mode, day, route)
);

-- The merchant-facing read: one merchant's usage in one mode across a date range.
create index idx_api_usage_daily_merchant
    on api_usage_daily (merchant_id, mode, day desc);

-- Which days have been rolled up, so retention can prove it is never dropping raw rows that
-- were never aggregated.
--
-- A "does api_usage_daily have rows for that day?" check cannot answer this: a day with no
-- traffic legitimately produces no rows, and is indistinguishable from a day whose rollup
-- never ran. Getting that backwards means deleting the only copy of data nobody aggregated,
-- which is unrecoverable — so the state is recorded explicitly rather than inferred.
create table api_usage_rollup_state (
    day             date        primary key,
    rows_aggregated bigint      not null,
    completed_at    timestamptz not null default now()
);
