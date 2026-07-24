-- M17.3: introduces the payment-method-token concept (D130) — a nullable column, no
-- backfill, no constraint change. A payment created without a token behaves exactly as
-- every payment did before M17: no sandbox call exists yet (M17.4), and once it does, a
-- null token resolves to the mode default (test -> auto-approve, live -> the simulated
-- acquirer) — the property that keeps this migration additive to every M16.2 test.

alter table payments add column payment_method_token varchar(64);
