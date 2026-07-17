-- ─────────────────────────────────────────────────────────────────────────
-- Postgres bootstrap (runs ONCE, on first container start with an empty volume).
--
-- Enforces the "database-per-service" pattern using one schema per service
-- inside a single shared database. Each service's Flyway migrations own the
-- tables inside its schema; services never join across schemas.
--
-- Local convenience only. On AWS (RDS) the equivalent isolation is provisioned
-- via Terraform in a later phase.
-- ─────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS merchant;
CREATE SCHEMA IF NOT EXISTS payment;
CREATE SCHEMA IF NOT EXISTS transaction;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS notification;
CREATE SCHEMA IF NOT EXISTS analytics;

-- UUID generation (gen_random_uuid) ships with pgcrypto; enable it up front so
-- migrations can rely on it regardless of which service runs first.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

COMMENT ON SCHEMA identity     IS 'identity-service: users, credentials, roles, refresh tokens';
COMMENT ON SCHEMA merchant     IS 'merchant-service: merchants, API keys';
COMMENT ON SCHEMA payment      IS 'payment-service: payments, idempotency keys, outbox';
COMMENT ON SCHEMA transaction  IS 'transaction-service: double-entry ledger';
COMMENT ON SCHEMA audit        IS 'audit-service: immutable audit trail';
COMMENT ON SCHEMA notification IS 'notification-service: webhook/email delivery state';
COMMENT ON SCHEMA analytics    IS 'analytics-service: read models / aggregates';
