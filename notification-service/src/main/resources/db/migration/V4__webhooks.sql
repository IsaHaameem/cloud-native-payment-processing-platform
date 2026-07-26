-- M18.1: the webhook subsystem's schema (§4.5/§4.6) — four new tables that turn "the
-- merchant's one URL" into a real subsystem: many endpoints per merchant per mode,
-- event-type subscriptions, a canonical merchant-facing event object, and a per-attempt
-- delivery log.
--
-- Numbering note: §5's M18 task list names this migration "V2__webhooks.sql", written
-- when notification's schema was still at V1. It has since gained V2 (M15's email_log
-- generalisation) and V3 (M16.6's mode columns), so this is V4. A Flyway ordering fact,
-- not a design change.
--
-- Deliberately additive only: nothing here alters processed_events, email_log, or
-- webhook_deliveries. V1's single-URL delivery path (D46) keeps running untouched
-- through M18.5 and is cut over in M18.6, so no commit in this milestone leaves the
-- platform half-migrated.
--
-- webhook_deliveries is retained and evolved rather than replaced: it already models
-- exactly the per-(event, endpoint) delivery aggregate M18 needs — status
-- PENDING/DELIVERED/DEAD_LETTERED, attempt_count, optimistic-lock version — and
-- webhook_delivery_attempts below is its per-attempt child. The columns that make it a
-- fan-out target (webhook_event_id, endpoint_id, and dropping the one-row-per-event
-- unique constraint) land in M18.6 alongside the writer that needs them.


-- ── webhook_endpoints ───────────────────────────────────────────────────────────────
-- Many per merchant, per mode. mode is NOT NULL here, unlike email_log's and
-- webhook_deliveries' nullable mode (D126): those are *recorders* that faithfully write
-- back whatever the source event declared, including nothing. This table is a
-- *partition* — a test endpoint receiving a live event would be precisely the
-- mode-isolation failure M16 exists to prevent — so it follows M16.2–16.4's NOT NULL
-- partitioning semantics instead. There are no pre-existing rows to backfill.
create table webhook_endpoints (
    id                         uuid          primary key default gen_random_uuid(),
    merchant_id                uuid          not null,
    mode                       varchar(4)    not null,
    url                        varchar(2048) not null,
    description                varchar(255),
    enabled                    boolean       not null default true,

    -- Pinned per endpoint so a merchant's existing integration keeps receiving the
    -- payload shape it was written against once date-based versioning (D108) is real.
    -- Until M21 ships that machinery there is exactly one value; the column exists now
    -- because backfilling a version pin onto live endpoints later is guesswork.
    api_version                varchar(20)   not null,

    -- Only the SHA-256 hash is stored (OpaqueTokenGenerator, the same treatment sk_/
    -- refresh tokens get, §4.9): the raw whsec_ value is shown exactly once at creation
    -- and never again. The prefix is kept in the clear so a merchant can tell two
    -- endpoints' secrets apart without re-exposing either.
    signing_secret_hash        varchar(64)   not null,
    signing_secret_prefix      varchar(20)   not null,

    -- Dual-secret rotation window (§4.5): after a rotation the superseded secret stays
    -- valid until previous_secret_expires_at, so an endpoint can roll without dropping
    -- deliveries. Expiry is a pure time comparison at read time — the same
    -- no-scheduler-required shape as ApiKey.rotateWithGrace (D120).
    previous_secret_hash       varchar(64),
    previous_secret_expires_at timestamptz,

    -- Auto-disable (§4.5): counts consecutive failures across *distinct* events, reset
    -- to 0 by any success. Reaching the configured threshold sets enabled=false plus the
    -- two disabled_* columns, so "disabled by the platform" is distinguishable from
    -- "disabled by the merchant" (which only clears enabled).
    consecutive_failure_count  int           not null default 0,
    disabled_at                timestamptz,
    disabled_reason            varchar(32),

    -- True for an endpoint adopted from V1's merchants.webhook_url rather than
    -- registered through the API (M18.9). Kept as data, not inferred later from a
    -- heuristic, so the deprecation of that column stays auditable.
    migrated_from_legacy       boolean       not null default false,

    created_at                 timestamptz   not null default now(),
    updated_at                 timestamptz   not null default now(),
    version                    bigint        not null default 0,

    constraint chk_webhook_endpoints_mode check (mode in ('test', 'live')),
    constraint chk_webhook_endpoints_url_not_blank check (length(btrim(url)) > 0),
    constraint chk_webhook_endpoints_failure_count_not_negative check (consecutive_failure_count >= 0),
    -- A previous secret without an expiry would never lapse; an expiry without a secret
    -- is meaningless. Both or neither — the coherent-shape discipline the sandbox
    -- migrations apply to overrides, applied to rotation state.
    constraint chk_webhook_endpoints_previous_secret_shape check (
        (previous_secret_hash is null) = (previous_secret_expires_at is null)),
    -- An auto-disabled endpoint is by definition not enabled. The reverse does not
    -- hold: a merchant-disabled endpoint has enabled=false with no disabled_at.
    constraint chk_webhook_endpoints_disabled_shape check (
        disabled_at is null or enabled = false),
    constraint chk_webhook_endpoints_disabled_reason check (
        disabled_reason is null or disabled_reason in ('CONSECUTIVE_FAILURES'))
);

-- One registration per URL per merchant per mode. Registering the same URL twice would
-- silently double every delivery to it — a duplicate-webhook bug the merchant would
-- diagnose as a platform fault. Scoped to (merchant_id, mode) so test and live may
-- legitimately point at the same URL.
create unique index uq_webhook_endpoints_merchant_mode_url
    on webhook_endpoints (merchant_id, mode, url);

-- The fan-out lookup (M18.6): "every enabled endpoint for this merchant in this mode".
create index idx_webhook_endpoints_merchant_mode_enabled
    on webhook_endpoints (merchant_id, mode) where enabled;


-- ── webhook_subscriptions ───────────────────────────────────────────────────────────
-- Which canonical event types each endpoint receives. '*' is a valid event_type and
-- means "everything", matching the wildcard convention API-key scopes already use
-- (MerchantContext.hasScope). No mode column: an endpoint is already mode-scoped, and
-- a subscription cannot be reached except through one.
create table webhook_subscriptions (
    id          uuid        primary key default gen_random_uuid(),
    endpoint_id uuid        not null references webhook_endpoints (id) on delete cascade,
    event_type  varchar(64) not null,
    created_at  timestamptz not null default now(),

    constraint uq_webhook_subscriptions_endpoint_event unique (endpoint_id, event_type),
    constraint chk_webhook_subscriptions_event_type_not_blank check (length(btrim(event_type)) > 0)
);


-- ── webhook_events ──────────────────────────────────────────────────────────────────
-- The canonical, merchant-facing event object (§4.5) — distinct from the internal Kafka
-- EventEnvelope. This is the row served by M19's Events API *and* serialized into the
-- webhook body, so "what the dashboard shows" and "what the endpoint received" are the
-- same object by construction rather than by two code paths agreeing.
--
-- event_ref is the public 'evt_...' identifier, derived deterministically from
-- source_event_id ('evt_' + the UUID's 32 hex digits) rather than being independently
-- random. That determinism is load-bearing for M19: audit-service stores the same
-- envelope eventId and must project its rows into this exact shape, and a derived id
-- lets it do so with no coordination, no shared sequence, and no lookup back into this
-- schema. Stored as a column (not computed on read) so it is indexable and queryable.
--
-- mode is NOT NULL and resolved at write time: an envelope carrying no mode is read as
-- 'live', which is D125's stated consumer semantics for a null mode. This differs from
-- the nullable, never-coerced mode on email_log/webhook_deliveries (D126) for the same
-- reason webhook_endpoints does — this table is queried *by* mode, so a null would be
-- unqueryable rather than merely unknown.
create table webhook_events (
    id              uuid         primary key default gen_random_uuid(),
    event_ref       varchar(40)  not null,
    source_event_id uuid         not null,
    merchant_id     uuid         not null,
    mode            varchar(4)   not null,
    event_type      varchar(64)  not null,
    api_version     varchar(20)  not null,
    data            jsonb        not null,
    occurred_at     timestamptz  not null,
    correlation_id  varchar(64),
    created_at      timestamptz  not null default now(),

    -- The dedup gate for fan-out: one internal event produces exactly one canonical
    -- event no matter how many times Kafka redelivers it (D2, at-least-once).
    constraint uq_webhook_events_source_event_id unique (source_event_id),
    constraint uq_webhook_events_event_ref unique (event_ref),
    constraint chk_webhook_events_mode check (mode in ('test', 'live')),
    constraint chk_webhook_events_event_ref_prefix check (event_ref like 'evt\_%')
);

-- M19's Events API list query: a merchant's own events, newest first, within one mode.
create index idx_webhook_events_merchant_mode_created
    on webhook_events (merchant_id, mode, created_at desc);


-- ── webhook_delivery_attempts ───────────────────────────────────────────────────────
-- One row per HTTP attempt: the full request actually sent and the full response
-- actually received. This is the delivery log the dashboard renders and the docs point
-- a stuck integrator at, so it records the request verbatim per attempt rather than
-- referencing webhook_events.data — a retry re-signs with a fresh timestamp and a replay
-- may use a rotated secret, so the bytes genuinely differ between attempts and a shared
-- reference would show the merchant something they were never sent.
--
-- Parent is webhook_deliveries (the per-(event, endpoint) aggregate), not
-- webhook_events: attempts belong to a delivery, and a replay is a new delivery with its
-- own attempts rather than extra attempts on the original (M18.8). No mode column —
-- unreachable except via a delivery whose endpoint is already mode-scoped, so carrying
-- one here would be denormalisation that can drift.
create table webhook_delivery_attempts (
    id               uuid          primary key default gen_random_uuid(),
    delivery_id      uuid          not null references webhook_deliveries (id) on delete cascade,
    attempt_number   int           not null,
    outcome          varchar(24)   not null,
    request_url      varchar(2048) not null,
    request_headers  jsonb         not null,
    request_body     text          not null,
    response_status  int,
    response_headers jsonb,
    -- Truncated to a configured cap before insert (M18.6): a hostile endpoint returning
    -- gigabytes must not be able to fill this table through us.
    response_body    text,
    duration_ms      int,
    error            varchar(512),
    attempted_at     timestamptz   not null default now(),

    constraint uq_webhook_delivery_attempts_delivery_number unique (delivery_id, attempt_number),
    constraint chk_webhook_delivery_attempts_number_positive check (attempt_number >= 1),
    constraint chk_webhook_delivery_attempts_duration_not_negative check (duration_ms is null or duration_ms >= 0),
    -- SUCCEEDED: a 2xx. FAILED_STATUS: the endpoint answered non-2xx. FAILED_TRANSPORT:
    -- connect/read failure or timeout — no status to record. BLOCKED: the egress guard
    -- (M18.5) refused to make the call at all, which is a distinct outcome from a failed
    -- call and must not be indistinguishable from one in the log.
    constraint chk_webhook_delivery_attempts_outcome check (
        outcome in ('SUCCEEDED', 'FAILED_STATUS', 'FAILED_TRANSPORT', 'BLOCKED')),
    -- A recorded status only makes sense when the endpoint actually answered.
    constraint chk_webhook_delivery_attempts_status_shape check (
        (outcome in ('SUCCEEDED', 'FAILED_STATUS')) = (response_status is not null))
);

-- The delivery-log query (M18.8): every attempt for one delivery, in order.
create index idx_webhook_delivery_attempts_delivery
    on webhook_delivery_attempts (delivery_id, attempt_number);
