-- M20.5 (D145): per-merchant rate-limit and quota overrides.
--
-- §5/M20 lists "configurable per-merchant limits" as a feature, but §4.6's merchant_settings
-- table was never built, so the plan named a store that does not exist. Rather than build a
-- general settings table for three integers, the overrides live on `merchants` and ride out
-- on the API-key verify response the gateway already resolves and caches on every request
-- (D145) — no second round trip, no second cache to invalidate.
--
-- All three are NULLABLE, and that is the design rather than laziness: null means "use the
-- platform default for this mode", so the defaults stay in one place (the gateway's
-- configuration) and can be changed for everyone without a data migration. A non-null value
-- is an explicit, per-merchant decision someone made — which is exactly the distinction an
-- operator needs when asking "why is this merchant limited differently?".
alter table merchants
    -- Sustained requests per second, and the burst the token bucket allows above it.
    add column rate_limit_per_second integer,
    add column rate_limit_burst      integer,
    -- Requests per UTC day, per mode. Counted separately for test and live so a load test in
    -- sandbox can never exhaust the budget a merchant's production traffic depends on.
    add column daily_quota           integer;

-- Guard the values rather than trusting whatever writes them. A zero or negative limit would
-- not mean "unlimited" to the token bucket — it would mean "refuse everything", turning a
-- misconfigured row into a total outage for one merchant. Rejecting it at the database keeps
-- that unrepresentable, the same reasoning as M19.2's chk_refunds_failure_shape.
alter table merchants
    add constraint chk_merchants_rate_limit_per_second check (rate_limit_per_second is null or rate_limit_per_second > 0),
    add constraint chk_merchants_rate_limit_burst      check (rate_limit_burst      is null or rate_limit_burst      > 0),
    add constraint chk_merchants_daily_quota           check (daily_quota           is null or daily_quota           > 0);
