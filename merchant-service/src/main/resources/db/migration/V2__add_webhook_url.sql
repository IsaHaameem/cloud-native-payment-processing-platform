-- Adds an optional per-merchant webhook delivery destination (M7). Nullable: a
-- merchant that hasn't configured one simply receives no webhook deliveries.
alter table merchants add column webhook_url varchar(2048);
