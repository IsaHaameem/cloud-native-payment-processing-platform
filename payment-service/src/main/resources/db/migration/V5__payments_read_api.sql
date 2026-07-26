-- M19.2: the filterable, cursor-paginated public payments list (§5/M19 task 2).
--
-- Additive throughout: no existing column changes type or nullability, so every M15–M18
-- caller and every existing test behaves exactly as before.

-- Free-form merchant-supplied key/value data (§4.6). Defaulted to an empty object rather
-- than left nullable: a merchant filtering on metadata should not have to reason about
-- the difference between "no metadata" and "null metadata", and `{} @> {"k":"v"}` is
-- simply false, which is the answer they want.
alter table payments add column metadata jsonb not null default '{}'::jsonb;

-- GIN with the default jsonb_ops, which is what makes the containment operator (@>)
-- index-backed. A btree index would not serve `metadata @> '{"order":"1234"}'` at all —
-- this is the one filter that genuinely needs a different index type, and the reason the
-- list query is the platform's first native query (see PaymentRepository).
create index idx_payments_metadata on payments using gin (metadata);

-- The list's ORDER BY and keyset predicate are (created_at desc, id desc) within a
-- (merchant, mode) partition. This composite covers the whole ordering, so the query is
-- an index scan rather than a sort of the merchant's entire history — the difference
-- between a list that stays fast as a merchant grows and one that does not.
--
-- Supersedes idx_payments_merchant_mode (M16.2): that index is the leftmost prefix of
-- this one, so keeping both would mean paying for two indexes on every insert to serve
-- one access pattern.
drop index idx_payments_merchant_mode;
create index idx_payments_merchant_mode_created on payments (merchant_id, mode, created_at desc, id desc);

-- Status is the most-used filter (a merchant polling for what settled), and it is
-- low-cardinality enough that Postgres will happily combine this with the index above via
-- a bitmap scan when both are constrained.
create index idx_payments_merchant_mode_status on payments (merchant_id, mode, status);
