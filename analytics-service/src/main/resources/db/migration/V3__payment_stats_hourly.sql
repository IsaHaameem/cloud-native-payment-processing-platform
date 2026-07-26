-- M19.6: time-bucketed payment statistics (§5/M19 task 5).
--
-- merchant_payment_stats is a running total: it answers "how much, ever" and nothing
-- about when. A volume or success-rate chart needs a series, and deriving one from a
-- single accumulating row is impossible — the information was never recorded.
--
-- Hourly rather than daily: a merchant debugging "what happened this afternoon" needs
-- finer resolution than a day, and hourly buckets roll up to daily trivially while the
-- reverse is not true. One row per (merchant, currency, mode, hour).
create table payment_stats_hourly (
    id                          uuid        primary key default gen_random_uuid(),
    merchant_id                 uuid        not null,
    currency                    varchar(3)  not null,
    mode                        varchar(4)  not null,
    -- The truncated hour this bucket covers, always UTC. Stored rather than derived so
    -- the unique constraint below can key on it.
    bucket_start                timestamptz not null,

    created_count               bigint      not null default 0,
    authorized_count            bigint      not null default 0,
    captured_count              bigint      not null default 0,
    refunded_count              bigint      not null default 0,
    voided_count                bigint      not null default 0,
    failed_count                bigint      not null default 0,
    total_captured_amount_minor bigint      not null default 0,
    total_refunded_amount_minor bigint      not null default 0,

    created_at                  timestamptz not null default now(),
    updated_at                  timestamptz not null default now(),
    version                     bigint      not null default 0,

    constraint chk_payment_stats_hourly_mode check (mode in ('test', 'live')),
    -- Same partitioning guarantee as merchant_payment_stats (M16.4): test and live series
    -- are structurally separate rows and can never be mixed by a query that forgets to
    -- filter.
    constraint uq_payment_stats_hourly_bucket unique (merchant_id, currency, mode, bucket_start)
);

-- The series read: one merchant's buckets in one mode over a time range, in order.
create index idx_payment_stats_hourly_series
    on payment_stats_hourly (merchant_id, mode, bucket_start desc);

-- No backfill. The hourly series starts when this table does — the historical
-- information it would need (which hour each past event fell in) exists only in
-- audit-service's trail, and reconstructing analytics from another service's data would
-- couple two schemas that D4 deliberately keeps apart. A merchant's running totals in
-- merchant_payment_stats remain complete and unaffected; only the series has a start
-- date, which is the truth rather than a fabricated history.
--
-- failed_count is new here and deliberately absent from merchant_payment_stats: M19 is
-- the first time anything reads these numbers, so the gap (a success *rate* needs a
-- denominator that includes failures) becomes visible now. The running-total table is
-- left alone rather than widened — adding a column there would need a backfill it cannot
-- honestly produce either.
