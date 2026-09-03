-- Project 3, agentic-commerce-service. The whole schema for the agentic commerce layer.
--
-- Two logical groups share one schema because they share one deployable and one lifecycle:
-- commerce (products, checkouts) and agency (conversations, actions, policy, approvals).
-- Nothing here is joined to any other service's schema — D4's schema-per-service rule holds,
-- and this service reaches payment data only through the public /v1 API.
--
-- Conventions inherited from the platform, deliberately and not by accident:
--   * money is ALWAYS an integer in the currency's minor unit; there is no numeric or float
--     column anywhere in this file (D36);
--   * every merchant-scoped table carries merchant_id and mode (M16);
--   * mode is 'test' only, enforced by constraint rather than convention — this extension is
--     test-mode-only by decision, and a schema constraint makes that structurally true
--     instead of merely intended.

-- ─────────────────────────────────────────────────────────────────────────────
-- Commerce
-- ─────────────────────────────────────────────────────────────────────────────

create table products (
    id                uuid          primary key default gen_random_uuid(),
    merchant_id       uuid          not null,
    mode              varchar(4)    not null,
    sku               varchar(64)   not null,
    name              varchar(200)  not null,
    description       varchar(2000),
    category          varchar(64)   not null,
    price_minor       bigint        not null,
    currency          varchar(3)    not null,
    inventory_count   int           not null,
    active            boolean       not null default true,
    metadata          jsonb         not null default '{}'::jsonb,
    created_at        timestamptz   not null default now(),
    updated_at        timestamptz   not null default now(),

    constraint chk_products_mode check (mode = 'test'),
    constraint chk_products_price_positive check (price_minor > 0),
    constraint chk_products_inventory_not_negative check (inventory_count >= 0),
    constraint uq_products_sku unique (merchant_id, mode, sku)
);

create index idx_products_merchant_mode_category on products (merchant_id, mode, category);
-- Search is a case-insensitive substring match over name and description. A trigram index
-- would serve it better, but pg_trgm is an extension this platform does not install and a
-- demo catalogue is measured in tens of rows: adding an extension to the deployment for a
-- query over 20 products would be a cost with no measurable benefit. Recorded so the first
-- milestone with a real catalogue knows the index is absent on purpose.
create index idx_products_merchant_mode_active on products (merchant_id, mode, active);

-- Checkout is the server-owned, authoritative statement of what is being bought and for how
-- much. Its total is computed here from its own items and is the ONLY amount that may ever
-- reach a payment: the LLM can name a checkout, never a price.
create table checkouts (
    id                 uuid          primary key default gen_random_uuid(),
    merchant_id        uuid          not null,
    mode               varchar(4)    not null,
    conversation_id    uuid,
    session_ref        varchar(128)  not null,
    status             varchar(24)   not null,
    currency           varchar(3)    not null,
    subtotal_minor     bigint        not null default 0,
    discount_minor     bigint        not null default 0,
    total_minor        bigint        not null default 0,
    payment_id         uuid,
    provider_reference varchar(128),
    expires_at         timestamptz   not null,
    created_at         timestamptz   not null default now(),
    updated_at         timestamptz   not null default now(),
    version            bigint        not null default 0,

    constraint chk_checkouts_mode check (mode = 'test'),
    constraint chk_checkouts_status check (status in ('OPEN', 'LOCKED', 'PAID', 'CANCELLED', 'EXPIRED')),
    constraint chk_checkouts_amounts_not_negative
        check (subtotal_minor >= 0 and discount_minor >= 0 and total_minor >= 0),
    constraint chk_checkouts_discount_within_subtotal check (discount_minor <= subtotal_minor),
    -- The total is derived, and the constraint says so. A row whose total disagrees with its
    -- own subtotal and discount is the single most dangerous thing this table could hold, so
    -- it is unrepresentable rather than guarded by application code alone.
    constraint chk_checkouts_total_is_derived check (total_minor = subtotal_minor - discount_minor),
    -- A paid checkout must name the payment that paid it. Without this, "already paid" could
    -- be asserted with nothing to point at.
    constraint chk_checkouts_paid_has_payment check (status <> 'PAID' or payment_id is not null)
);

create index idx_checkouts_merchant_mode_status on checkouts (merchant_id, mode, status);
create index idx_checkouts_session on checkouts (merchant_id, mode, session_ref);
create index idx_checkouts_conversation on checkouts (conversation_id);
create index idx_checkouts_payment on checkouts (payment_id);

create table checkout_items (
    id                uuid          primary key default gen_random_uuid(),
    checkout_id       uuid          not null references checkouts (id) on delete cascade,
    product_id        uuid          not null references products (id),
    sku               varchar(64)   not null,
    name              varchar(200)  not null,
    quantity          int           not null,
    -- Captured at the moment the item entered the checkout. A later catalogue price change
    -- must not silently re-price a quote a customer has already been shown, and a quote whose
    -- total can drift under it is not a quote.
    unit_price_minor  bigint        not null,
    line_total_minor  bigint        not null,
    created_at        timestamptz   not null default now(),

    constraint chk_checkout_items_quantity_positive check (quantity > 0),
    constraint chk_checkout_items_quantity_bounded check (quantity <= 100),
    constraint chk_checkout_items_price_positive check (unit_price_minor > 0),
    constraint chk_checkout_items_line_total_is_derived
        check (line_total_minor = unit_price_minor * quantity),
    constraint uq_checkout_items_product unique (checkout_id, product_id)
);

create index idx_checkout_items_checkout on checkout_items (checkout_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Agency
-- ─────────────────────────────────────────────────────────────────────────────

create table conversations (
    id                uuid          primary key default gen_random_uuid(),
    merchant_id       uuid          not null,
    mode              varchar(4)    not null,
    session_ref       varchar(128)  not null,
    status            varchar(16)   not null,
    -- Cumulative spend and refund in minor units, read by the policy engine's budget rules.
    -- Maintained here rather than recomputed from agent_actions on every evaluation: a budget
    -- check runs before every money action and must not become a scan.
    spent_minor       bigint        not null default 0,
    refunded_minor    bigint        not null default 0,
    tool_call_count   int           not null default 0,
    created_at        timestamptz   not null default now(),
    updated_at        timestamptz   not null default now(),
    version           bigint        not null default 0,

    constraint chk_conversations_mode check (mode = 'test'),
    constraint chk_conversations_status check (status in ('ACTIVE', 'CLOSED')),
    constraint chk_conversations_counters_not_negative
        check (spent_minor >= 0 and refunded_minor >= 0 and tool_call_count >= 0)
);

create index idx_conversations_merchant_mode_session on conversations (merchant_id, mode, session_ref);

create table conversation_messages (
    id                uuid          primary key default gen_random_uuid(),
    conversation_id   uuid          not null references conversations (id) on delete cascade,
    role              varchar(16)   not null,
    -- Redacted before it ever arrives here. Never the raw provider payload.
    content           text          not null,
    sequence_no       int           not null,
    created_at        timestamptz   not null default now(),

    constraint chk_conversation_messages_role check (role in ('USER', 'ASSISTANT', 'TOOL')),
    constraint uq_conversation_messages_sequence unique (conversation_id, sequence_no)
);

create index idx_conversation_messages_conversation on conversation_messages (conversation_id, sequence_no);

-- One row per tool call. The unit the MODEL is accountable for.
create table agent_actions (
    id                     uuid          primary key default gen_random_uuid(),
    merchant_id            uuid          not null,
    mode                   varchar(4)    not null,
    conversation_id        uuid          not null references conversations (id),
    correlation_id         varchar(64)   not null,
    tool_name              varchar(64)   not null,
    tool_category          varchar(24)   not null,
    -- A redacted, schema-projected canonical summary of the validated arguments. Never raw
    -- model output, and never anything that could carry a credential.
    input_summary          text          not null,
    state                  varchar(24)   not null,
    -- Duplicated from policy_decisions deliberately: this is the field every listing filters
    -- on, and a join to answer "was this refused?" on the hot path would be a cost paid for
    -- normalisation nobody asked for. policy_decisions stays the authoritative append-only
    -- record of every evaluation.
    policy_decision        varchar(24),
    approval_id            uuid,
    checkout_id            uuid,
    payment_id             uuid,
    failure_code           varchar(64),
    failure_message        varchar(1000),
    -- What the budget allowed at the moment of decision. Without it, "this action was within
    -- budget" is unfalsifiable after the fact, which would make the bounded claim decorative.
    budget_remaining_minor bigint,
    llm_model              varchar(128),
    prompt_version         varchar(32),
    created_at             timestamptz   not null default now(),
    completed_at           timestamptz,

    constraint chk_agent_actions_mode check (mode = 'test'),
    constraint chk_agent_actions_state check (state in (
        'PROPOSED', 'VALIDATED', 'REFUSED', 'APPROVAL_REQUIRED', 'EXECUTING', 'EXECUTED', 'FAILED')),
    constraint chk_agent_actions_policy_decision
        check (policy_decision is null or policy_decision in ('PERMIT', 'REFUSE', 'REQUIRES_APPROVAL'))
);

create index idx_agent_actions_conversation on agent_actions (conversation_id, created_at desc);
create index idx_agent_actions_merchant_mode on agent_actions (merchant_id, mode, created_at desc);
create index idx_agent_actions_correlation on agent_actions (correlation_id);
create index idx_agent_actions_payment on agent_actions (payment_id);
create index idx_agent_actions_checkout on agent_actions (checkout_id);

-- One row per platform operation attempted within an action. The unit the PLATFORM is
-- accountable for. A composite money tool performs several of these, and a flat action row
-- could not honestly record one that created a payment, authorized it, then failed at capture.
create table agent_action_steps (
    id                  uuid          primary key default gen_random_uuid(),
    agent_action_id     uuid          not null references agent_actions (id) on delete cascade,
    sequence_no         int           not null,
    operation           varchar(64)   not null,
    -- The derived key, stored so a replay can be PROVEN to have been a replay rather than
    -- asserted. This column is what makes the no-duplicate-charge claim evidenced.
    idempotency_key     varchar(128),
    correlation_id      varchar(64)   not null,
    request_id          varchar(64),
    http_status         int,
    state               varchar(16)   not null,
    payment_id          uuid,
    provider_reference  varchar(128),
    failure_code        varchar(64),
    failure_message     varchar(1000),
    created_at          timestamptz   not null default now(),
    completed_at        timestamptz,

    constraint chk_agent_action_steps_state check (state in (
        'NOT_ATTEMPTED', 'IN_FLIGHT', 'SUCCEEDED', 'FAILED', 'REPLAYED')),
    constraint uq_agent_action_steps_sequence unique (agent_action_id, sequence_no)
);

create index idx_agent_action_steps_action on agent_action_steps (agent_action_id, sequence_no);
create index idx_agent_action_steps_idempotency on agent_action_steps (idempotency_key);

-- Append-only record of every policy evaluation. 1:N with agent_actions, not 1:1: an action
-- that stops at REQUIRES_APPROVAL is evaluated a SECOND time when the approval is granted,
-- and both evaluations are part of the record. That re-evaluation is the reason this is a
-- table rather than four more columns on agent_actions.
create table policy_decisions (
    id                  uuid          primary key default gen_random_uuid(),
    agent_action_id     uuid          not null references agent_actions (id) on delete cascade,
    policy_version      varchar(32)   not null,
    rule_id             varchar(64)   not null,
    decision            varchar(24)   not null,
    reason_code         varchar(64)   not null,
    reason              varchar(500)  not null,
    actor               varchar(128)  not null,
    tool_name           varchar(64)   not null,
    resource            varchar(128),
    evaluated_at        timestamptz   not null default now(),

    constraint chk_policy_decisions_decision
        check (decision in ('PERMIT', 'REFUSE', 'REQUIRES_APPROVAL'))
);

create index idx_policy_decisions_action on policy_decisions (agent_action_id, evaluated_at);

create table approvals (
    id                  uuid          primary key default gen_random_uuid(),
    merchant_id         uuid          not null,
    mode                varchar(4)    not null,
    agent_action_id     uuid          not null references agent_actions (id) on delete cascade,
    conversation_id     uuid          not null references conversations (id),
    tool_name           varchar(64)   not null,
    requested_operation varchar(64)   not null,
    checkout_id         uuid,
    payment_id          uuid,
    -- The exact amount the approver is agreeing to, frozen at request time: an approval must
    -- authorise the amount that was shown and nothing else. The execution path re-reads this
    -- and refuses if the resolved amount has moved since.
    amount_minor        bigint,
    currency            varchar(3),
    state               varchar(16)   not null,
    reason              varchar(500),
    decided_by          varchar(128),
    created_at          timestamptz   not null default now(),
    expires_at          timestamptz   not null,
    decided_at          timestamptz,

    constraint chk_approvals_mode check (mode = 'test'),
    constraint chk_approvals_state check (state in ('PENDING', 'APPROVED', 'DENIED', 'EXPIRED', 'CONSUMED')),
    constraint chk_approvals_amount_positive check (amount_minor is null or amount_minor > 0),
    -- One approval per action. An action with two live approvals would make "who authorised
    -- this" ambiguous at exactly the moment it matters most.
    constraint uq_approvals_action unique (agent_action_id)
);

create index idx_approvals_merchant_mode_state on approvals (merchant_id, mode, state, created_at desc);

alter table agent_actions
    add constraint fk_agent_actions_approval foreign key (approval_id) references approvals (id);
alter table agent_actions
    add constraint fk_agent_actions_checkout foreign key (checkout_id) references checkouts (id);
