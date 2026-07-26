-- M19.3: refunds become first-class objects (§5/M19 task 2).
--
-- Before this, a refund existed only as an increment to payments.refunded_amount_minor.
-- That is enough to answer "how much has been refunded" and nothing else — not when,
-- not how many, not which one failed. A merchant reconciling against their own records
-- needs an object with an id.

create table refunds (
    id                uuid         primary key default gen_random_uuid(),
    payment_id        uuid         not null references payments (id),
    -- Denormalized from the payment rather than joined at read time: every refund query
    -- is merchant- and mode-scoped (D101), and carrying both here means the scoping is a
    -- predicate on this table rather than a join the caller could forget to add.
    merchant_id       uuid         not null,
    mode              varchar(4)   not null,
    amount_minor      bigint       not null,
    currency          varchar(3)   not null,
    status            varchar(20)  not null,
    reason            varchar(500),
    failure_reason    varchar(500),
    metadata          jsonb        not null default '{}'::jsonb,
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now(),
    version           bigint       not null default 0,

    constraint chk_refunds_mode check (mode in ('test', 'live')),
    constraint chk_refunds_amount_positive check (amount_minor > 0),
    constraint chk_refunds_status check (status in ('SUCCEEDED', 'FAILED')),
    -- A FAILED refund must say why; a SUCCEEDED one must not pretend it failed.
    constraint chk_refunds_failure_shape check (
        (status = 'FAILED' and failure_reason is not null)
        or (status = 'SUCCEEDED' and failure_reason is null))
);

-- The list's ordering and keyset predicate, same shape as payments'.
create index idx_refunds_merchant_mode_created on refunds (merchant_id, mode, created_at desc, id desc);
-- The expand=refunds lookup: every refund of one payment, oldest first.
create index idx_refunds_payment on refunds (payment_id, created_at);
create index idx_refunds_metadata on refunds using gin (metadata);

-- payments.refunded_amount_minor and captured_amount_minor are deliberately NOT dropped.
-- §5/M19 task 2 keeps them "as derived values", and there are two reasons beyond that:
-- the FSM in Payment.refund() reads refunded_amount_minor to decide REFUNDED vs
-- PARTIALLY_REFUNDED, which is M5 logic this milestone has no business rewriting; and
-- refunds issued before M19 exist only as that running total, so the column is the only
-- record of them. New refunds write both — the row and the accumulator — in the same
-- transaction, so they cannot disagree.
--
-- No backfill: a pre-M19 payment's total cannot be decomposed into the individual refunds
-- that produced it (only the sum was ever stored), and inventing one synthetic refund per
-- historical total would fabricate a timestamp and an id that never existed. Historical
-- payments therefore report a refunded total with no refund objects behind it, which is
-- the truth.
