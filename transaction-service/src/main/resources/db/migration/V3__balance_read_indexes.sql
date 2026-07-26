-- M19.8: the two indexes the balance read surface needs, added because a plan said so
-- rather than because a design document assumed so.
--
-- M19's own risk table asks for `EXPLAIN` on each list endpoint "rather than assumed".
-- M19.4 shipped without that check, and running it against 600k seeded ledger entries
-- found both queries below scanning where they should have been seeking. Neither is
-- visible at the volumes any test or the local compose stack produces, which is exactly
-- why the check had to be a measurement.

-- ── GET /v1/balance_transactions ────────────────────────────────────────────
--
-- The list is keyset-paginated on (created_at desc, id desc) within a merchant's set of
-- accounts. idx_ledger_entries_account_id (V1) can find those rows but cannot order
-- them, so Postgres read *every* entry the merchant had ever accumulated and top-N
-- sorted it — once per page. That is O(a merchant's whole history) to return 25 rows,
-- which is the unbounded query M19's risk table warns about, wearing a LIMIT.
--
-- Measured on an account with 200,000 entries:
--   before: Parallel Bitmap Heap Scan + top-N heapsort, 5,962 buffers, 24.7 ms
--   after:  Index Scan, 19 buffers, 0.035 ms
--
-- Leading with account_id because scoping is always by the merchant's resolved accounts;
-- created_at/id descending because that is the list's exact ORDER BY, which lets the
-- keyset predicate become an index range condition rather than a filter.
create index idx_ledger_entries_account_created
    on ledger_entries (account_id, created_at desc, id desc);

-- idx_ledger_entries_account_id is deliberately NOT dropped, unlike M19.2's supersession
-- of idx_payments_merchant_mode. It is the leftmost prefix of the new index and so is
-- redundant for lookups — but it also backs the ledger_entries.account_id foreign key,
-- and dropping the only index on an FK column makes every delete on transaction.accounts
-- scan this table. Accounts are not deleted today; a schema change that has to be
-- reasoned about later is worse than one small redundant index now.

-- ── GET /v1/balance ─────────────────────────────────────────────────────────
--
-- Resolving a merchant's accounts by (owner_id, mode) had no index at all: the existing
-- uq_accounts_merchant is unique on (account_type, owner_id, currency, mode), and
-- account_type leading means it cannot serve a lookup that does not name one. Every
-- balance read was a sequential scan of the accounts table.
--
-- Small today (two rows per merchant per mode per currency) and unbounded in the only
-- direction that matters: it grows with the number of merchants on the platform, and
-- this is the query every dashboard load starts with.
create index idx_accounts_owner_mode on accounts (owner_id, mode);
