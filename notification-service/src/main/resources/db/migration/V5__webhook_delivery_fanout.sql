-- M18.6: the cutover from V1's single-URL delivery to endpoint fan-out, plus a defect
-- fix carried over from M18.2 (D137).
--
-- ── Part 1: signing secrets become encrypted, not hashed (D137) ─────────────────────
--
-- M18.2 stored signing_secret_hash, following §4.9's "every secret is stored only as
-- SHA-256". That rule is correct for sk_ keys and refresh tokens, which the platform only
-- ever *verifies* (hash what was presented, compare). It is not implementable for a
-- webhook signing secret, which the platform must *use* as an HMAC key on every delivery:
-- a one-way digest cannot produce a signature the merchant — who holds the original —
-- could reproduce. Signing with the hash would emit deliveries that no receiver on earth
-- can verify.
--
-- The columns are dropped and replaced rather than converted because a SHA-256 digest
-- cannot be turned back into the secret it came from: there is no migration path for an
-- existing value, only re-issuance. Safe in practice — webhook_endpoints was created two
-- sub-milestones ago (M18.1) and has never existed in a deployed environment; any row
-- created on a local machine during M18.2–M18.5 must be re-registered to obtain a usable
-- secret. Recorded here rather than silently, because "your endpoint stopped verifying"
-- is exactly the failure this note prevents someone from having to diagnose.
alter table webhook_endpoints drop column signing_secret_hash;
alter table webhook_endpoints drop column previous_secret_hash;

-- AES-256-GCM, base64 of iv||ciphertext||tag. Recoverable by this service, useless from a
-- database dump alone.
alter table webhook_endpoints add column signing_secret_encrypted   varchar(255) not null default '';
alter table webhook_endpoints add column previous_secret_encrypted  varchar(255);
alter table webhook_endpoints alter column signing_secret_encrypted drop default;

-- The dual-secret coherence constraint referenced the dropped column; restate it against
-- the new one. (Dropping a column drops constraints that depend on it, so this is a
-- re-creation, not a duplicate.)
alter table webhook_endpoints add constraint chk_webhook_endpoints_previous_secret_shape check (
    (previous_secret_encrypted is null) = (previous_secret_expires_at is null));


-- ── Part 2: webhook_deliveries becomes a fan-out target ─────────────────────────────
--
-- One canonical event now produces N deliveries — one per subscribed endpoint — where V1
-- produced exactly one per event. The table keeps its identity (status, attempt_count,
-- optimistic-lock version) and its retry/DLQ semantics; only its grain changes.
alter table webhook_deliveries add column webhook_event_id uuid references webhook_events (id);
alter table webhook_deliveries add column endpoint_id      uuid references webhook_endpoints (id) on delete cascade;

-- When the next retry is due (M18.7's explicit schedule). Null means "no further attempt
-- is scheduled" — either resolved, or the very first dispatch which happens immediately.
alter table webhook_deliveries add column next_attempt_at timestamptz;

-- A replay (M18.8) is a new delivery that points back at the one it re-sends, so the
-- original's history stays exactly what happened the first time.
alter table webhook_deliveries add column replayed_from_delivery_id uuid references webhook_deliveries (id);

-- V1's one-row-per-event rule is exactly what fan-out breaks: three subscribed endpoints
-- mean three rows for one event. Replaced by uniqueness on the pair that is genuinely
-- unique — and even that only for non-replays, since a replay is deliberately a second
-- delivery of the same event to the same endpoint.
alter table webhook_deliveries drop constraint uq_webhook_deliveries_event_id;
create unique index uq_webhook_deliveries_event_endpoint
    on webhook_deliveries (webhook_event_id, endpoint_id)
    where replayed_from_delivery_id is null;

-- V1 wrote these from the payment event; fan-out sources the URL from the endpoint row.
-- Kept (nullable) rather than dropped: existing rows are a real delivery history, and
-- dropping a column is irreversible for no gain (the same reasoning §13-Q9 applies to
-- merchants.webhook_url).
alter table webhook_deliveries alter column event_id    drop not null;
alter table webhook_deliveries alter column webhook_url drop not null;
alter table webhook_deliveries alter column payload     drop not null;

-- The dispatch and retry sweeps read by these.
create index idx_webhook_deliveries_endpoint on webhook_deliveries (endpoint_id);
create index idx_webhook_deliveries_next_attempt on webhook_deliveries (next_attempt_at)
    where status = 'PENDING';
