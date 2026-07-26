-- M20.3: the developer-visible API request log (§5/M20 task 5, §4.6).
--
-- This is the highest write-volume table on the platform by construction: one row per API
-- request, versus one row per payment lifecycle event everywhere else. D116 decided its
-- retention story in the same milestone as the table itself, and §4.6 sets it at 30 days
-- with a pre-pruning rollup into api_usage_daily (M20.4).
--
-- Partitioned by day on occurred_at. Dropping a partition is a metadata operation; deleting
-- 30 days of rows from one giant table is hours of vacuum pressure on the busiest table in
-- the system. That difference is the entire reason for the partitioning, and it only pays
-- off if the pruner drops partitions rather than issuing DELETEs — which is what M20.4 does.
create table api_request_log (
    id              uuid        not null default gen_random_uuid(),

    -- The envelope's eventId. At-least-once delivery (D2) means this consumer will see
    -- redeliveries, and a request log that double-counts is a usage-metering bug that
    -- becomes a billing bug the moment anyone charges for it.
    event_id        uuid        not null,

    merchant_id     uuid        not null,
    key_id          uuid,
    mode            varchar(4)  not null,

    method          varchar(10) not null,
    path            text        not null,
    query_string    text,
    status_code     integer     not null,
    duration_ms     bigint      not null,

    client_ip       varchar(45),
    user_agent      text,
    correlation_id  varchar(64),
    request_id      varchar(64),
    error_code      varchar(40),

    -- Already redacted and capped by the gateway before serialization (M20.1/M20.2). Stored
    -- as text rather than jsonb deliberately: a captured body may be truncated mid-structure
    -- or may not be JSON at all, and jsonb would reject exactly the malformed payloads a
    -- developer most needs to see.
    request_body    text,
    response_body   text,
    request_headers jsonb       not null default '{}',

    occurred_at     timestamptz not null,
    created_at      timestamptz not null default now(),

    constraint chk_api_request_log_mode check (mode in ('test', 'live')),

    -- The partition key must be part of every unique constraint on a partitioned table, so
    -- both keys below carry occurred_at. That is a Postgres requirement, not a design
    -- preference, and it is why de-duplication is (event_id, occurred_at) rather than
    -- event_id alone — safe here because a redelivered event carries the same occurred_at.
    primary key (id, occurred_at),
    constraint uq_api_request_log_event unique (event_id, occurred_at)
) partition by range (occurred_at);

-- The merchant-facing list read: one merchant's requests in one mode, newest first. Declared
-- on the parent so every partition — including ones created months from now by the partition
-- manager — inherits it automatically.
create index idx_api_request_log_merchant
    on api_request_log (merchant_id, mode, occurred_at desc, id desc);

-- Supports filtering the list by outcome, the second-most-likely question after "what did I
-- send" ("what failed").
create index idx_api_request_log_status
    on api_request_log (merchant_id, mode, status_code, occurred_at desc);

-- A DEFAULT partition as a safety valve, and the reason is worth recording. A range-
-- partitioned table with no partition covering "now" does not degrade — it *rejects the
-- insert outright*, so a partition manager that falls behind by one tick would silently stop
-- the request log at midnight. The default catches those rows instead of losing them.
--
-- It is expected to stay empty: RequestLogPartitionManager keeps several days of partitions
-- ahead of time. The trade accepted here is that attaching a real partition for a day the
-- default already holds rows for would fail — which is fine, because partitions are only
-- ever created ahead, never backfilled, and the pruner deletes from the default by date
-- rather than dropping it.
create table api_request_log_default partition of api_request_log default;

-- Bootstrap partitions so the table is usable the moment this migration lands, without
-- waiting for the manager's first tick. Seven days, matching the manager's own lookahead.
do $$
declare
    day date := current_date;
begin
    for i in 0..6 loop
        execute format(
            'create table if not exists api_request_log_%s partition of api_request_log '
            || 'for values from (%L) to (%L)',
            to_char(day + i, 'YYYYMMDD'),
            (day + i)::timestamptz,
            (day + i + 1)::timestamptz);
    end loop;
end
$$;
