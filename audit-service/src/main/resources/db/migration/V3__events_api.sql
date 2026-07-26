-- M19.5: make audit_log queryable by merchant (§5/M19 task 4).
--
-- Found while building the Events API, not during planning: audit_log has never had a
-- merchant column. That was correct for what it was — a faithful, schema-agnostic
-- recorder (D44) that stores whatever envelope arrived and needs to know nothing about
-- its shape. But a merchant-facing Events API must be merchant-scoped (D101/D102), and
-- "scope by digging into a jsonb field on every query" is neither indexable nor a
-- guarantee: it is a convention a future query could forget.
--
-- So the merchant becomes a real column. The recorder's character is unchanged — payload
-- is still stored verbatim and still uninterpreted; this extracts exactly one identifier
-- for scoping, and nothing else.
alter table audit_log add column merchant_id uuid;

-- Backfill from the payload. Both streams audit consumes carry merchantId at the top
-- level of their payload (payment.events via PaymentEventPayload, merchant.events via
-- MerchantEventPayload), so this recovers the merchant for every existing row that has
-- one. A row whose payload has no merchantId keeps null and is simply never returned by
-- the merchant-scoped API — which is correct: an event belonging to no merchant is not a
-- merchant's event.
update audit_log
set merchant_id = (payload ->> 'merchantId')::uuid
where merchant_id is null
  and payload ->> 'merchantId' is not null
  -- Guard against a malformed value making the whole migration fail: only cast what
  -- actually looks like a UUID.
  and payload ->> 'merchantId' ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$';

-- Deliberately left NULLABLE, unlike the mode-partitioned tables in M16.2–16.4: audit
-- consumes streams whose events genuinely have no merchant, and D126 already established
-- for this table that inventing a value to satisfy a constraint is a lie in an immutable
-- trail. A null merchant means "this event belongs to no merchant", and the API's
-- equality predicate excludes those rows without needing a special case.

-- The Events API's ordering and keyset predicate: newest-occurred first within a
-- (merchant, mode) partition.
create index idx_audit_log_merchant_mode_occurred on audit_log (merchant_id, mode, occurred_at desc, id desc);
