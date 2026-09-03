-- Project 3, agentic-commerce-service — G-6.
--
-- Persists the provider decision that payment-service asks this service for. Until now the
-- verdict was computed and returned but nothing kept it, so a merchant had no way to see —
-- after the fact — whether a payment the agent made was backed by a real cardholder
-- authorisation or by a Razorpay `order_accepted` demonstration stand-in. That distinction is
-- the single most important honesty control in the whole integration (project_3_context.md
-- AD-11 / §46.5), and a control nobody can inspect is not a control.
--
-- Provider-neutral, deliberately: no order id, no Razorpay error taxonomy, no provider-specific
-- column. The same vocabulary sandbox-service and the /internal decision contract already speak
-- — outcome / declineCode / errorCode / source — plus the `demo` flag and the `amount`/`currency`
-- the request carried. `provider_name` is the adapter that answered, for reconciliation.

create table provider_decisions (
    id                 uuid          primary key default gen_random_uuid(),
    merchant_id        uuid          not null,
    mode               varchar(4)    not null,
    payment_id         uuid          not null,
    -- payment-service's idempotency key for this authorization attempt. Persisting is idempotent
    -- on it: a retried decision call for the same attempt neither inserts nor changes a row.
    decision_key       varchar(128)  not null,
    operation          varchar(32)   not null,
    outcome            varchar(16)   not null,
    decline_code       varchar(64),
    error_code         varchar(64),
    -- payment_collected | order_accepted | provider_unavailable | provider_not_configured.
    -- `order_accepted` NEVER means a card was authorised — see ProviderDecision.
    source             varchar(32)   not null,
    provider_reference  varchar(128),
    -- true ONLY for the demonstration stand-in (source=order_accepted, outcome=APPROVE). Anything
    -- reporting an approval to a person must show this.
    demo               boolean       not null default false,
    provider_name      varchar(32)   not null,
    amount_minor       bigint        not null,
    currency           varchar(3)    not null,
    correlation_id     varchar(64),
    created_at         timestamptz   not null default now(),

    constraint chk_provider_decisions_mode check (mode = 'test'),
    constraint chk_provider_decisions_outcome check (outcome in ('APPROVE', 'DECLINE', 'ERROR')),
    constraint chk_provider_decisions_amount_positive check (amount_minor > 0),
    constraint uq_provider_decisions_key unique (decision_key)
);

create index idx_provider_decisions_merchant_mode_payment
    on provider_decisions (merchant_id, mode, payment_id, created_at desc);
create index idx_provider_decisions_merchant_mode
    on provider_decisions (merchant_id, mode, created_at desc);
